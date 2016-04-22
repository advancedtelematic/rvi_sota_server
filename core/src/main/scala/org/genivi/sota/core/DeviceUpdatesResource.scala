/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Uuid
import io.circe.Json
import io.circe.generic.auto._
import org.genivi.sota.core.common.NamespaceDirective._
import org.genivi.sota.core.data.response.{PendingUpdateResponse, ResponseConversions}
import org.genivi.sota.core.db.Devices
import org.genivi.sota.core.resolver.{Connectivity, DefaultConnectivity, ExternalResolverClient}
import org.genivi.sota.core.rvi.InstallReport
import org.genivi.sota.core.storage.PackageStorage
import org.genivi.sota.core.transfer.{DefaultUpdateNotifier, InstalledPackagesUpdate, PackageDownloadProcess}
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.{PackageId, Device}
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.rest.Validation.refined
import org.joda.time.DateTime
import scala.language.implicitConversions
import slick.driver.MySQLDriver.api.Database


class DeviceUpdatesResource(db : Database, resolverClient: ExternalResolverClient)
                           (implicit system: ActorSystem, mat: ActorMaterializer,
                            connectivity: Connectivity = DefaultConnectivity) extends Directives {

  import Json.{obj, string}
  import WebService._

  implicit val ec = system.dispatcher
  implicit val _db = db
  implicit val _config = system.settings.config

  lazy val packageRetrievalOp = (new PackageStorage).retrieveResponse _

  lazy val packageDownloadProcess = new PackageDownloadProcess(db, packageRetrievalOp)

  protected lazy val updateService = new UpdateService(DefaultUpdateNotifier)

  def logDeviceSeen(ns: Namespace, uuid: Device.Id): Directive0 = {
    extractRequestContext flatMap { _ =>
      onComplete(db.run(Devices.updateLastSeen(ns, uuid)))
    } flatMap (_ => pass)
  }

  def updateInstalledPackages(uuid: Device.Id): Route = {
    entity(as[List[PackageId]]) { ids =>
      val f = InstalledPackagesUpdate
        .update(uuid, ids, resolverClient)
        .map(_ => NoContent)

      complete(f)
    }
  }

  def pendingPackages(ns: Namespace, uuid: Device.Id) = {
    import PendingUpdateResponse._
    import ResponseConversions._

    logDeviceSeen(ns, uuid) {
      val devicePackages =
        InstalledPackagesUpdate
          .findPendingPackageIdsFor(ns, uuid)
          .map(_.toResponse)

      complete(db.run(devicePackages))
    }
  }

  def downloadPackage(uuid: Refined[String, Uuid]): Route = {
    withRangeSupport {
      val responseF = packageDownloadProcess.buildClientDownloadResponse(uuid)
      complete(responseF)
    }
  }

  def reportInstall(uuid: Refined[String, Uuid]): Route = {
    entity(as[InstallReport]) { report =>
      val responseF =
        InstalledPackagesUpdate
          .buildReportInstallResponse(report.device, report.update_report)
      complete(responseF)
    }
  }

  def queueDeviceUpdate(ns: Namespace, uuid: Device.Id): Route = {
    entity(as[PackageId]) { packageId =>
      val result = updateService.queueDeviceUpdate(ns, uuid, packageId)
      complete(result)
    }
  }

  def sync(ns: Namespace, uuid: Device.Id): Route = {
    val ttl = DateTime.now.plusMinutes(5)
    // TODO: Config RVI destination path (or ClientServices.getpackages)
    // TODO: pass namespace
    connectivity.client.sendMessage(s"genivi.org/device/${uuid.toString}/sota/getpackages", io.circe.Json.Null, ttl)
    // TODO: Confirm getpackages in progress to device?
    complete(NoContent)
  }

  val route = {
    (pathPrefix("api" / "v1" / "device_updates") & extractUuid) { deviceUuid =>
      (post & pathEnd) { updateInstalledPackages(toUUID(deviceUuid)) } ~
      (post & extractNamespace & pathEnd) { ns => queueDeviceUpdate(ns, toUUID(deviceUuid)) } ~
      (get & extractNamespace & pathEnd) { ns => pendingPackages(ns, toUUID(deviceUuid)) } ~
      (get & extractUuid & path("download")) { downloadPackage } ~
      (post & extractUuid) { reportInstall } ~
      (post & extractNamespace & path("sync")) { ns => sync(ns, toUUID(deviceUuid)) }
    }
  }
}
