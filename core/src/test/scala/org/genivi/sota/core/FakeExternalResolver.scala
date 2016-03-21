package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpResponse, Uri}
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import io.circe.Json
import org.genivi.sota.core.data.{Package, Vehicle}
import org.genivi.sota.core.rvi.InstalledPackages

import scala.concurrent.Future

class FakeExternalResolver()(implicit system: ActorSystem, mat: ActorMaterializer) extends DefaultExternalResolverClient(Uri.Empty, Uri.Empty, Uri.Empty, Uri.Empty)
{
  val installedPackages = scala.collection.mutable.Queue.empty[InstalledPackages]

  override def setInstalledPackages(vin: Vehicle.Vin, json: Json): Future[Unit] = {
    installedPackages.enqueue(InstalledPackages(vin, json))
    Future.successful(())
  }

  override def resolve(packageId: Package.Id): Future[Map[Vehicle, Set[Package.Id]]] = ???

  override def handlePutResponse(futureResponse: Future[HttpResponse]): Future[Unit] = ???

  override def putPackage(packageId: Package.Id, description: Option[String], vendor: Option[String]): Future[Unit] = ???
}
