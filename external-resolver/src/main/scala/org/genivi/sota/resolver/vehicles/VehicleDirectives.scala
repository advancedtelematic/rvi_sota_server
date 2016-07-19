/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.vehicles


import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import io.circe.generic.auto._
import org.genivi.sota.common.DeviceRegistry
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.{Device, Namespace, PackageId}
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.marshalling.RefinedMarshallingSupport._
import org.genivi.sota.resolver.common.InstalledSoftware
import org.genivi.sota.resolver.common.Errors
import org.genivi.sota.resolver.common.RefinementDirectives.{refinedPackageId, refinedPartNumber}
import org.genivi.sota.resolver.components.Component
import org.genivi.sota.rest.Validation._

import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._


/**
 * API routes for everything related to vehicles: creation, deletion, and package and component association.
 *
 * @see {@linktourl http://advancedtelematic.github.io/rvi_sota_server/dev/api.html}
 */
class VehicleDirectives(namespaceExtractor: Directive1[Namespace],
                        deviceRegistry: DeviceRegistry)
                       (implicit system: ActorSystem,
                        db: Database,
                        mat: ActorMaterializer,
                        ec: ExecutionContext) {
  import Directives._

  /**
   * Exception handler for package routes.
   */
  def installedPackagesHandler: ExceptionHandler =
    ExceptionHandler(Errors.onMissingPackage orElse Errors.onMissingVehicle)

  /**
   * Exception handler for component routes.
   */
  def installedComponentsHandler: ExceptionHandler =
    ExceptionHandler(Errors.onMissingVehicle orElse Errors.onMissingComponent)

  val extractDeviceId : Directive1[Device.Id] = refined[Device.ValidId](Slash ~ Segment).map(Device.Id)

  def searchDevices(ns: Namespace): Route =
    parameters(('regex.as[String Refined Regex].?,
      'packageName.as[PackageId.Name].?,
      'packageVersion.as[PackageId.Version].?,
      'component.as[Component.PartNumber].?)) { case (re, pn, pv, cp) =>
      complete(VehicleRepository.search(ns, re, pn, pv, cp, deviceRegistry))
    }
  def getPackages(device: Device.Id): Route =
    complete(db.run(VehicleRepository.installedOn(device)))

  def installPackage(namespace: Namespace, device: Device.Id, pkgId: PackageId): Route =
    completeOrRecoverWith(db.run(VehicleRepository.installPackage(namespace, device, pkgId))) {
      Errors.onMissingVehicle orElse Errors.onMissingPackage
    }

  def uninstallPackage(ns: Namespace, device: Device.Id, pkgId: PackageId): Route =
    completeOrRecoverWith(db.run(VehicleRepository.uninstallPackage(ns, device, pkgId))) {
      Errors.onMissingVehicle orElse Errors.onMissingPackage
    }

  def updateInstalledSoftware(device: Device.Id): Route = {
    def updateSoftwareOnDb(namespace: Namespace, installedSoftware: InstalledSoftware): Future[Unit] = {
      db.run {
        for {
          _ <- VehicleRepository.updateInstalledPackages(namespace, device, installedSoftware.packages)
          _ <- VehicleRepository.updateInstalledFirmware(device, installedSoftware.firmware)
        } yield ()
      }
    }

    entity(as[InstalledSoftware]) { installedSoftware =>
      val responseF = {
        for {
          deviceData <- deviceRegistry.fetchDevice(device)
          _ <- updateSoftwareOnDb(deviceData.namespace, installedSoftware)
        } yield ()
      }

      onSuccess(responseF) { complete(StatusCodes.NoContent) }
    }
  }

  /**
   * API route for package -> vehicle associations.
   *
   * @return      Route object containing routes for listing packages on a vehicle, and creating and deleting
   *              vehicle -> package associations
   * @throws      Errors.MissingPackageException if package doesn't exist
   * @throws      Errors.MissingVehicle if vehicle doesn't exist
   */
  def packageApi(device: Device.Id): Route = {
    (pathPrefix("package") & namespaceExtractor) { ns =>
      (get & pathEnd) {
        getPackages(device)
      } ~
      refinedPackageId { pkgId =>
        (put & pathEnd) {
          installPackage(ns, device, pkgId)
        } ~
        (delete & pathEnd) {
          uninstallPackage(ns, device, pkgId)
        }
      }
    } ~
    (path("packages") & put & handleExceptions(installedPackagesHandler)) {
      updateInstalledSoftware(device)
    }
  }

  def getComponents(ns: Namespace, device: Device.Id): Route =
    completeOrRecoverWith(db.run(VehicleRepository.componentsOnDevice(ns, device))) {
        Errors.onMissingVehicle
      }

  def installComponent(ns: Namespace, device: Device.Id, part: Component.PartNumber): Route =
    complete(db.run(VehicleRepository.installComponent(ns, device, part)))

  def uninstallComponent(ns: Namespace, device: Device.Id, part: Component.PartNumber): Route =
    complete(db.run(VehicleRepository.uninstallComponent(ns, device, part)))

  /**
   * API route for component -> vehicle associations.
   *
   * @return      Route object containing routes for listing components on a vehicle, and creating and deleting
   *              vehicle -> component associations
   * @throws      Errors.MissingComponent if component doesn't exist
   * @throws      Errors.MissingVehicle if vehicle doesn't exist
   */
  def componentApi(device: Device.Id): Route =
    (pathPrefix("component") & namespaceExtractor) { ns =>
      (get & pathEnd) {
        getComponents(ns, device)
      } ~
      (refinedPartNumber & handleExceptions(installedComponentsHandler)) { part =>
        (put & pathEnd) {
          installComponent(ns, device, part)
        } ~
        (delete & pathEnd) {
          uninstallComponent(ns, device, part)
        }
      }
    }

  def vehicleApi: Route =
    pathPrefix("vehicles") {
      namespaceExtractor { ns =>
        (get & pathEnd) { searchDevices(ns) }
      } ~
      extractDeviceId { device =>
        packageApi(device) ~
          componentApi(device)
      }
    }

  def getFirmware(ns: Namespace, deviceId: Device.Id): Route =
    completeOrRecoverWith(db.run(VehicleRepository.firmwareOnDevice(ns, deviceId))) {
      Errors.onMissingVehicle
    }

  /**
   * Base API route for vehicles.
   *
   * @return      Route object containing routes for creating, deleting, and listing vehicles
   * @throws      Errors.MissingVehicle if vehicle doesn't exist
   */
  def route: Route = {
    vehicleApi ~
    (pathPrefix("firmware") & get & namespaceExtractor & extractDeviceId) { (ns, device) =>
      getFirmware(ns, device)
    }
  }
}
