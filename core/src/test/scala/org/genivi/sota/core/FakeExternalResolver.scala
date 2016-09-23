package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model.{HttpResponse, Uri}
import akka.stream.ActorMaterializer
import io.circe.Json
import org.genivi.sota.core.resolver.{DefaultExternalResolverClient, ExternalResolverClient}
import org.genivi.sota.data.{Device, Namespace, PackageId, Uuid}

import scala.concurrent.Future

class FakeExternalResolver()(implicit system: ActorSystem, mat: ActorMaterializer)
  extends ExternalResolverClient
{
  import org.genivi.sota.marshalling.CirceInstances._

  type Request[T] = FutureClientRequest[T]
  private def mkReq[T](f: Future[T]) = FutureClientRequest(f)

  private val installedPackages = scala.collection.mutable.Queue.empty[PackageId]

  private val logger = Logging.getLogger(system, this)

  override def setInstalledPackages(device: Uuid, json: Json): Request[Unit] = mkReq {
    val ids = json
      .cursor
      .downField("packages")
      .map(_.as[List[PackageId]].getOrElse(List.empty))
      .toSeq
      .flatten

    installedPackages.enqueue(ids:_*)
    Future.successful(())
  }

  override def resolve(namespace: Namespace, packageId: PackageId): Request[Map[Uuid, Set[PackageId]]] = mkReq {
    Future.successful(Map.empty)
  }

  override def putPackage(namespace: Namespace,
                          packageId: PackageId,
                          description: Option[String],
                          vendor: Option[String]): Request[Unit] = mkReq {
    logger.info(s"Fake resolver called. namespace=$namespace, packageId=${packageId.mkString}")
    Future.successful(())
  }

  override def affectedDevices(namespace: Namespace, packageIds: Set[PackageId]): Request[Map[Uuid, Seq[PackageId]]] = mkReq {
    Future.successful(Map.empty)
  }

  def isInstalled(packageId: PackageId): Boolean = installedPackages.contains(packageId)
}
