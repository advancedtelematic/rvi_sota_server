/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.devices

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.NoContent
import akka.http.scaladsl.server._
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import io.circe.generic.auto._
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.PackageId
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.marshalling.RefinedMarshallingSupport._
import org.genivi.sota.resolver.common.Errors
import org.genivi.sota.resolver.common.NamespaceDirective._
import org.genivi.sota.resolver.common.RefinementDirectives.{refinedPackageId, refinedPartNumber}
import org.genivi.sota.resolver.components.{Component, ComponentRepository}
import org.genivi.sota.resolver.packages.Package
import org.genivi.sota.rest.Validation._
import org.genivi.sota.rest.{ErrorCode, ErrorRepresentation}
import scala.concurrent.ExecutionContext
import slick.dbio.{DBIOAction, DBIO}
import slick.jdbc.JdbcBackend.Database


/**
 * API routes for everything related to devices: creation, deletion, and package and component association.
 *
 * @see {@linktourl http://pdxostc.github.io/rvi_sota_server/dev/api.html}
 */
class DeviceDirectives(implicit system: ActorSystem,
                       db: Database,
                       mat: ActorMaterializer,
                       ec: ExecutionContext) {
  import Directives._

  /**
   * Exception handler for package routes.
   */
  def installedPackagesHandler =
    ExceptionHandler(Errors.onMissingPackage orElse Errors.onMissingDevice)

  /**
   * Exception handler for component routes.
   */
  def installedComponentsHandler =
    ExceptionHandler(Errors.onMissingDevice orElse Errors.onMissingComponent)

  val extractDeviceId : Directive1[Device.DeviceId] = refined[Device.ValidDeviceId](Slash ~ Segment)

  def searchDevices(ns: Namespace) =
    parameters(('regex.as[String Refined Regex].?,
                'packageName.as[PackageId.Name].?,
                'packageVersion.as[PackageId.Version].?,
                'component.as[Component.PartNumber].?)) { case (re, pn, pv, cp) =>
      complete(db.run(DeviceRepository.search(ns, re, pn, pv, cp)))
    }

  def getDevice(ns: Namespace, vin: Device.DeviceId): Route =
    completeOrRecoverWith(db.run(DeviceRepository.exists(ns, vin))) {
      Errors.onMissingDevice
    }

  def addDevice(ns: Namespace, vin: Device.DeviceId): Route =
    complete(db.run(DeviceRepository.add(Device(ns, vin))).map(_ => NoContent))

  def deleteDevice(ns: Namespace, vin: Device.DeviceId): Route =
    handleExceptions(installedPackagesHandler) {
      complete(db.run(DeviceRepository.deleteDevice(ns, vin)))
    }

  def getPackages(ns: Namespace, vin: Device.DeviceId): Route =
    completeOrRecoverWith(db.run(DeviceRepository.packagesOnDevice(ns, vin))) {
      Errors.onMissingDevice
    }

  def installPackage(ns: Namespace, vin: Device.DeviceId, pkgId: PackageId): Route =
    completeOrRecoverWith(db.run(DeviceRepository.installPackage(ns, vin, pkgId))) {
      Errors.onMissingDevice orElse Errors.onMissingPackage
    }

  def uninstallPackage(ns: Namespace, vin: Device.DeviceId, pkgId: PackageId): Route =
    completeOrRecoverWith(db.run(DeviceRepository.uninstallPackage(ns, vin, pkgId))) {
      Errors.onMissingDevice orElse Errors.onMissingPackage
    }

  def updateInstalledPackages(ns: Namespace, vin: Device.DeviceId): Route =
    entity(as[Set[PackageId]]) { packageIds =>
      onSuccess(db.run(DeviceRepository.updateInstalledPackages(ns, vin, packageIds))) {
        complete(StatusCodes.NoContent)
      }
    }

  /**
   * API route for package -> device associations.
   *
   * @return      Route object containing routes for listing packages on a device, and creating and deleting
   *              device -> package associations
   * @throws      Errors.MissingPackageException if package doesn't exist
   * @throws      Errors.MissingDevice if device doesn't exist
   */
  def packageApi(vin: Device.DeviceId): Route = {
    (pathPrefix("package") & extractNamespace) { ns =>
      (get & pathEnd) {
        getPackages(ns, vin)
      } ~
      refinedPackageId { pkgId =>
        (put & pathEnd) {
          installPackage(ns, vin, pkgId)
        } ~
        (delete & pathEnd) {
          uninstallPackage(ns, vin, pkgId)
        }
      }
    } ~
    (path("packages") & put & handleExceptions(installedPackagesHandler) & extractNamespace ) { ns =>
      updateInstalledPackages(ns, vin)
    }
  }

  def getComponents(ns: Namespace, vin: Device.DeviceId): Route =
    completeOrRecoverWith(db.run(DeviceRepository.componentsOnDevice(ns, vin))) {
        Errors.onMissingDevice
      }

  def installComponent(ns: Namespace, vin: Device.DeviceId, part: Component.PartNumber): Route =
    complete(db.run(DeviceRepository.installComponent(ns, vin, part)))

  def uninstallComponent(ns: Namespace, vin: Device.DeviceId, part: Component.PartNumber): Route =
    complete(db.run(DeviceRepository.uninstallComponent(ns, vin, part)))

  /**
   * API route for component -> device associations.
   *
   * @return      Route object containing routes for listing components on a device, and creating and deleting
   *              device -> component associations
   * @throws      Errors.MissingComponent if component doesn't exist
   * @throws      Errors.MissingDevice if device doesn't exist
   */
  def componentApi(vin: Device.DeviceId): Route =
    (pathPrefix("component") & extractNamespace) { ns =>
      (get & pathEnd) {
        getComponents(ns, vin)
      } ~
      (refinedPartNumber & handleExceptions(installedComponentsHandler)) { part =>
        (put & pathEnd) {
          installComponent(ns, vin, part)
        } ~
        (delete & pathEnd) {
          uninstallComponent(ns, vin, part)
        }
      }
    }

  def deviceApi: Route =
    (pathPrefix("devices") & extractNamespace) { ns =>
      (get & pathEnd) {
        searchDevices(ns)
      } ~
      extractDeviceId { vin =>
        (get & pathEnd) {
          getDevice(ns, vin)
        } ~
        (put & pathEnd) {
          addDevice(ns, vin)
        } ~
        (delete & pathEnd) {
          deleteDevice(ns, vin)
        } ~
        packageApi(vin) ~
        componentApi(vin)
      }
    }

  def getFirmware(ns: Namespace, vin: Device.DeviceId): Route =
    completeOrRecoverWith(db.run(DeviceRepository.firmwareOnDevice(ns, vin))) {
      Errors.onMissingDevice
    }

  /**
   * Base API route for devices.
   *
   * @return      Route object containing routes for creating, deleting, and listing devices
   * @throws      Errors.MissingDevice if device doesn't exist
   */
  def route: Route = {
    deviceApi ~
    pathPrefix("firmware") {
      (get & pathEnd & extractNamespace & extractDeviceId) { (ns, vin) =>
        getFirmware(ns, vin)
      }
    }
  }

}
