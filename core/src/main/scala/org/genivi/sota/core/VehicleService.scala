/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import eu.timepit.refined.string.Uuid
import org.genivi.sota.core.rvi.InstallReport
import org.genivi.sota.core.transfer.{InstalledPackagesUpdate, PackageDownloadProcess}
import org.genivi.sota.data.{PackageId, Vehicle}
import slick.driver.MySQLDriver.api.Database
import io.circe.generic.auto._
import org.genivi.sota.core.db.Vehicles
import org.genivi.sota.rest.Validation.refined
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import io.circe.Json
import org.genivi.sota.core.resolver.ExternalResolverClient

class VehicleService(db : Database, resolverClient: ExternalResolverClient)
                    (implicit system: ActorSystem, mat: ActorMaterializer) extends Directives {
  implicit val log = Logging(system, "vehicleservice")

  import Json.{obj, string}

  val packageDownloadProcess = new PackageDownloadProcess(db)

  val extractUuid = refined[Uuid](Slash ~ Segment)

  implicit val ec = system.dispatcher
  implicit val _db = db

  def logVehicleSeen(vin: Vehicle.Vin): Directive0 = {
    extractRequestContext flatMap { _ =>
      onComplete(db.run(Vehicles.updateLastSeen(vin)))
    } flatMap (_ => pass)
  }

  def updateInstalledPackages(vin: Vehicle.Vin) = {
    (pathEnd & post) {
      entity(as[List[PackageId]]) { ids =>
        val f = InstalledPackagesUpdate
          .update(vin, ids, resolverClient)
          .map(_ => NoContent)

        complete(f)
      }
    }
  }

  def pendingPackages(vin: Vehicle.Vin) = {
    (get & logVehicleSeen(vin) & pathEnd) {
      val vehiclePackages = InstalledPackagesUpdate.findPendingPackageIdsFor(vin)
      complete(db.run(vehiclePackages))
    }
  }

  def downloadPackage = {
    (get & withRangeSupport & extractUuid & path("download")) { uuid =>
      val responseF = packageDownloadProcess.buildClientDownloadResponse(uuid)
      complete(responseF)
    }
  }

  def reportInstall = {
    (post & extractUuid) { uuid =>
      entity(as[InstallReport]) { report =>
        val responseF =
          InstalledPackagesUpdate
            .buildReportInstallResponse(report.vin, report.update_report)
        complete(responseF)
      }
    }
  }

  def buildVehicleRoutes(vinRoutes: (Vehicle.Vin, VehicleService) => Route): Route = {
    (pathPrefix("api" / "v1" / "vehicles") &
      VehiclesResource.extractVin &
      pathPrefix("updates")).apply(vinRoutes(_, this))
  }

  val route = buildVehicleRoutes { (vin: Vehicle.Vin, _) =>
    updateInstalledPackages(vin) ~
    pendingPackages(vin) ~
    downloadPackage ~
    reportInstall
  }
}
