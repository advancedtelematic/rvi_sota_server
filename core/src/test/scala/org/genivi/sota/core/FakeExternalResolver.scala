package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpResponse, Uri}
import akka.stream.ActorMaterializer
import io.circe.Json
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.{PackageId, Vehicle}

import scala.concurrent.Future

class FakeExternalResolver()(implicit system: ActorSystem, mat: ActorMaterializer) extends DefaultExternalResolverClient(Uri.Empty, Uri.Empty, Uri.Empty, Uri.Empty)
{
  val installedPackages = scala.collection.mutable.Queue.empty[PackageId]

  override def setInstalledPackages(vin: Vehicle.Vin, json: Json): Future[Unit] = {
    val ids = json.as[List[PackageId]].getOrElse(List.empty)
    installedPackages.enqueue(ids:_*)
    Future.successful(())
  }

  override def resolve(namespace: Namespace, packageId: PackageId): Future[Map[Vehicle, Set[PackageId]]] = ???

  override def handlePutResponse(futureResponse: Future[HttpResponse]): Future[Unit] = ???

  override def putPackage(namespace: Namespace, packageId: PackageId, description: Option[String], vendor: Option[String]): Future[Unit] = ???
}
