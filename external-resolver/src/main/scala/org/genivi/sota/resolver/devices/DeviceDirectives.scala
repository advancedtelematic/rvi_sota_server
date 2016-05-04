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

  def getDevice(ns: Namespace, deviceId: Device.DeviceId): Route =
    completeOrRecoverWith(db.run(DeviceRepository.exists(ns, deviceId))) {
      Errors.onMissingDevice
    }

  def addDevice(ns: Namespace, deviceId: Device.DeviceId): Route =
    complete(db.run(DeviceRepository.add(Device(ns, deviceId))).map(_ => NoContent))

  def deleteDevice(ns: Namespace, deviceId: Device.DeviceId): Route =
    handleExceptions(installedPackagesHandler) {
      complete(db.run(DeviceRepository.deleteDevice(ns, deviceId)))
    }

  def getPackages(ns: Namespace, deviceId: Device.DeviceId): Route =
    completeOrRecoverWith(db.run(DeviceRepository.packagesOnDevice(ns, deviceId))) {
      Errors.onMissingDevice
    }

  def installPackage(ns: Namespace, deviceId: Device.DeviceId, pkgId: PackageId): Route =
    completeOrRecoverWith(db.run(DeviceRepository.installPackage(ns, deviceId, pkgId))) {
      Errors.onMissingDevice orElse Errors.onMissingPackage
    }

  def uninstallPackage(ns: Namespace, deviceId: Device.DeviceId, pkgId: PackageId): Route =
    completeOrRecoverWith(db.run(DeviceRepository.uninstallPackage(ns, deviceId, pkgId))) {
      Errors.onMissingDevice orElse Errors.onMissingPackage
    }

  def updateInstalledPackages(ns: Namespace, deviceId: Device.DeviceId): Route =
    entity(as[Set[PackageId]]) { packageIds =>
      onSuccess(db.run(DeviceRepository.updateInstalledPackages(ns, deviceId, packageIds))) {
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
  def packageApi(deviceId: Device.DeviceId): Route = {
    (pathPrefix("package") & extractNamespace) { ns =>
      (get & pathEnd) {
        getPackages(ns, deviceId)
      } ~
      refinedPackageId { pkgId =>
        (put & pathEnd) {
          installPackage(ns, deviceId, pkgId)
        } ~
        (delete & pathEnd) {
          uninstallPackage(ns, deviceId, pkgId)
        }
      }
    } ~
    (path("packages") & put & handleExceptions(installedPackagesHandler) & extractNamespace ) { ns =>
      updateInstalledPackages(ns, deviceId)
    }
  }

  def getComponents(ns: Namespace, deviceId: Device.DeviceId): Route =
    completeOrRecoverWith(db.run(DeviceRepository.componentsOnDevice(ns, deviceId))) {
        Errors.onMissingDevice
      }

  def installComponent(ns: Namespace, deviceId: Device.DeviceId, part: Component.PartNumber): Route =
    complete(db.run(DeviceRepository.installComponent(ns, deviceId, part)))

  def uninstallComponent(ns: Namespace, deviceId: Device.DeviceId, part: Component.PartNumber): Route =
    complete(db.run(DeviceRepository.uninstallComponent(ns, deviceId, part)))

  /**
   * API route for component -> device associations.
   *
   * @return      Route object containing routes for listing components on a device, and creating and deleting
   *              device -> component associations
   * @throws      Errors.MissingComponent if component doesn't exist
   * @throws      Errors.MissingDevice if device doesn't exist
   */
  def componentApi(deviceId: Device.DeviceId): Route =
    (pathPrefix("component") & extractNamespace) { ns =>
      (get & pathEnd) {
        getComponents(ns, deviceId)
      } ~
      (refinedPartNumber & handleExceptions(installedComponentsHandler)) { part =>
        (put & pathEnd) {
          installComponent(ns, deviceId, part)
        } ~
        (delete & pathEnd) {
          uninstallComponent(ns, deviceId, part)
        }
      }
    }

  def deviceApi: Route =
    (pathPrefix("devices") & extractNamespace) { ns =>
      (get & pathEnd) {
        searchDevices(ns)
      } ~
      extractDeviceId { deviceId =>
        (get & pathEnd) {
          getDevice(ns, deviceId)
        } ~
        (put & pathEnd) {
          addDevice(ns, deviceId)
        } ~
        (delete & pathEnd) {
          deleteDevice(ns, deviceId)
        } ~
        packageApi(deviceId) ~
        componentApi(deviceId)
      }
    }

  def getFirmware(ns: Namespace, deviceId: Device.DeviceId): Route =
    completeOrRecoverWith(db.run(DeviceRepository.firmwareOnDevice(ns, deviceId))) {
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
      (get & pathEnd & extractNamespace & extractDeviceId) { (ns, deviceId) =>
        getFirmware(ns, deviceId)
      }
    }
  }

}
