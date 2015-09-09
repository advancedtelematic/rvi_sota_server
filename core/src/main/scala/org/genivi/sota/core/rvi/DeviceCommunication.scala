/**
  * Copyright: Copyright (C) 2015, Jaguar Land Rover
  * License: MPL-2.0
  */
package org.genivi.sota.core.rvi

import akka.actor.Props
import akka.http.scaladsl.model.HttpResponse
import java.net.URL
import org.genivi.sota.core.data.{InstallRequest, Package}
import org.genivi.sota.core.db.InstallRequests
import org.genivi.sota.core.files.Types
import org.joda.time.DateTime
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import slick.driver.MySQLDriver.api._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object DeviceCommunication {
  type ErrorLogger = (Throwable => Unit)
}

class DeviceCommunication(db : Database,
                          rviNode: RviInterface,
                          resolveFile: Types.Resolver,
                          logError: DeviceCommunication.ErrorLogger)
                         (implicit ec: ExecutionContext) {

  val node: RviInterface = rviNode

  private def notify(payload: (InstallRequest, Package)): Future[Try[InstallRequest]] = payload match {
    case (req, pack) => rviNode.notify(req.vin, pack)
        .map(_ => Success(req))
        .recover { case e@_ => Failure(e) }
  }

  private val DefaultChunkSize = 512
  private val DefaultAckTimeout = DurationInt(3).seconds
  private val Services: List[String] = List("initiate_download")

  def createTransfer(transactionId: Long,
                     destination: String,
                     packageIdentifier: String): Either[String, Props] =
    resolveFile(packageIdentifier).right.map { case (file, checksum) =>
      Props(classOf[Transfer],
            transactionId,
            destination,
            file,
            packageIdentifier,
            checksum,
            rviNode
      )
    }

  def registerServices(networkAddress: URL): Future[List[HttpResponse]] =
    Future.sequence(Services.map(rviNode.registerService(networkAddress, _)))

  def runCurrentCampaigns(): Future[Unit] = for {
    reqsWithPackages <- db.run(InstallRequests.currentAt(DateTime.now))
    allResponses <- Future.sequence(reqsWithPackages.map(notify _))
    successful = allResponses.collect { case Success(x) => x }
    failed = allResponses.collect { case Failure(e) => logError(e) }
    _ <- db.run(InstallRequests.updateNotified(successful))
  } yield ()
}
