/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.util.FastFuture
import cats.Show
import eu.timepit.refined._
import eu.timepit.refined.string._
import org.genivi.sota.core.common.Namespaces
import org.genivi.sota.core.data._
import org.genivi.sota.core.db._
import org.genivi.sota.core.rvi.ServerServices
import org.genivi.sota.core.transfer.UpdateNotifier
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.{PackageId, Vehicle}
import scala.collection.immutable.ListSet
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace
import slick.dbio.DBIO
import slick.driver.MySQLDriver.api.Database


case class PackagesNotFound(packageIds: (PackageId)*)
                           (implicit show: Show[PackageId])
    extends Throwable(s"""Package(s) not found: ${packageIds.map(show.show).mkString(", ")}""") with NoStackTrace

case class UploadConf( chunkSize: Int, downloadSplitStrategy: Set[Package] => Vector[Download] )

object UploadConf {

  implicit val default = UploadConf(
    chunkSize = 64 * 1024,
    downloadSplitStrategy = packages => {
      packages.map(p => Download.apply(Vector(p))).toVector
    }
  )

}

class UpdateService(notifier: UpdateNotifier)
                   (implicit val system: ActorSystem, val connectivity: Connectivity)
                   extends Namespaces {

  import UpdateService._

  implicit private val log = Logging(system, "updateservice")

  def checkVins( dependencies: VinsToPackages ) : Future[Boolean] = FastFuture.successful( true )

  def mapIdsToPackages(vinsToDeps: VinsToPackages )
                      (implicit db: Database, ec: ExecutionContext): Future[Map[PackageId, Package]] = {
    def mapPackagesToIds( packages: Seq[Package] ) : Map[PackageId, Package] = packages.map( x => x.id -> x).toMap

    def missingPackages( required: Set[PackageId], found: Seq[Package] ) : Set[PackageId] = {
      val result = required -- found.map( _.id )
      if( result.nonEmpty ) log.debug( s"Some of required packages not found: $result" )
      result
    }

    log.debug(s"Dependencies from resolver: $vinsToDeps")
    val requirements : Set[PackageId]  =
      vinsToDeps.foldLeft(Set.empty[PackageId])((acc, vinDeps) => acc.union(vinDeps._2) )
    for {
      foundPackages <- db.run(Packages.byIds(extractNamespace, requirements))
      mapping       <- if( requirements.size == foundPackages.size ) {
                         FastFuture.successful( mapPackagesToIds( foundPackages ) )
                       } else {
                         FastFuture.failed( PackagesNotFound( missingPackages(requirements, foundPackages).toArray: _*))
                       }
    } yield mapping

  }

  def loadPackage( id : PackageId)
                 (implicit db: Database, ec: ExecutionContext): Future[Package] = {
    db.run(Packages.byId(extractNamespace, id)).flatMap { x =>
      x.fold[Future[Package]](FastFuture.failed( PackagesNotFound(id)) )(FastFuture.successful)
    }
  }

  def mkUploadSpecs(request: UpdateRequest, vinsToPackageIds: VinsToPackages,
                    idsToPackages: Map[PackageId, Package]): Set[UpdateSpec] = {
    vinsToPackageIds.map {
      case (vin, requiredPackageIds) =>
        val packages : Set[Package] = requiredPackageIds.map( idsToPackages.get ).map( _.get )
        UpdateSpec(request.namespace, request, vin, UpdateStatus.Pending, packages)
    }.toSet
  }

  def persistRequest(request: UpdateRequest, updateSpecs: Set[UpdateSpec])
                    (implicit db: Database, ec: ExecutionContext) : Future[Unit] = {
    db.run(
      DBIO.seq(UpdateRequests.persist(request) +: updateSpecs.map( UpdateSpecs.persist ).toArray: _*)).map( _ => ()
    )
  }

  def queueUpdate(request: UpdateRequest, resolver : DependencyResolver )
                 (implicit db: Database, ec: ExecutionContext): Future[Set[UpdateSpec]] = {
    for {
      pckg           <- loadPackage(request.packageId)
      vinsToDeps     <- resolver(pckg)
      packages       <- mapIdsToPackages(vinsToDeps)
      updateSpecs    = mkUploadSpecs(request, vinsToDeps, packages)
      _              <- persistRequest(request, updateSpecs)
      _              <- Future.successful(notifier.notify(updateSpecs.toSeq))
    } yield updateSpecs
  }

  def queueVehicleUpdate(namespace: Namespace, vin: Vehicle.Vin, packageId: PackageId)
                        (implicit db: Database, ec: ExecutionContext): Future[UpdateRequest] = {
    val newUpdateRequest = UpdateRequest.default(namespace, packageId)

    for {
      p <- loadPackage(packageId)
      updateRequest = newUpdateRequest.copy(signature = p.signature.getOrElse(newUpdateRequest.signature),
        description = p.description)
      spec = UpdateSpec(namespace, updateRequest, vin, UpdateStatus.Pending, Set.empty)
      dbSpec <- persistRequest(updateRequest, ListSet(spec))
    } yield updateRequest
  }

  def all(implicit db: Database, ec: ExecutionContext): Future[Set[UpdateRequest]] =
    db.run(UpdateRequests.list).map(_.toSet)
}

object UpdateService {
  type VinsToPackages = Map[Vehicle.Vin, Set[PackageId]]
  type DependencyResolver = Package => Future[VinsToPackages]
}
