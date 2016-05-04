/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model.StatusCodes.NoContent
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directive1, Directives, Route, StandardRoute}
import akka.stream.ActorMaterializer
import eu.timepit.refined._
import eu.timepit.refined.string._
import io.circe.generic.auto._
import io.circe.syntax._
import org.genivi.sota.core.common.NamespaceDirective._
import org.genivi.sota.core.data._
import org.genivi.sota.core.db.{InstallHistories, UpdateSpecs, Devices}
import org.genivi.sota.core.resolver.{ConnectivityClient, ExternalResolverClient}
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.Device
import org.genivi.sota.marshalling.CirceMarshallingSupport
import org.genivi.sota.rest.ErrorRepresentation
import org.genivi.sota.rest.Validation._
import org.joda.time.DateTime
import scala.concurrent.{ExecutionContext, Future}
import scala.languageFeature.implicitConversions
import scala.languageFeature.postfixOps
import slick.driver.MySQLDriver.api.Database


class DevicesResource(db: Database, client: ConnectivityClient, resolverClient: ExternalResolverClient)
                      (implicit system: ActorSystem, mat: ActorMaterializer) {

  import CirceMarshallingSupport._
  import Directives._
  import WebService._
  import system.dispatcher

  case class DeviceWithoutNs(
    uuid: Device.Id,
    deviceId: Device.DeviceId,
    deviceType: Device.DeviceType)

  def extractDevice(ns: Namespace): Directive1[Device] = entity(as[DeviceWithoutNs]).flatMap { device =>
    provide(Device(namespace = ns,
                   uuid = device.uuid,
                   deviceId = device.deviceId,
                   deviceType = device.deviceType))
  }

  implicit val _db = db

  case object MissingDevice extends Throwable

  def exists(ns: Namespace, uuid: Device.Id)
    (implicit ec: ExecutionContext): Future[Device] =
    db.run(Devices.exists(ns, uuid))
      .flatMap(_
        .fold[Future[Device]]
          (Future.failed(MissingDevice))(Future.successful))

  def deleteDevice(ns: Namespace, uuid: Device.Id)
  (implicit ec: ExecutionContext): Future[Unit] =
    for {
      _ <- exists(ns: Namespace, uuid: Device.Id)
      _ <- db.run(UpdateSpecs.deleteRequiredPackageByDevice(ns, uuid))
      _ <- db.run(UpdateSpecs.deleteUpdateSpecByDevice(ns, uuid))
      _ <- db.run(Devices.deleteById(ns, uuid))
    } yield ()

  def fetchDevice(ns: Namespace, uuid: Device.Id): Route  = {
    completeOrRecoverWith(exists(ns, uuid)) {
      case MissingDevice =>
        complete(StatusCodes.NotFound ->
          ErrorRepresentation(ErrorCodes.MissingDevice, "Device doesn't exist"))
    }
  }

  def updateDevice(device: Device): Route = {
    complete(db.run(Devices.create(device)).map(_ => NoContent))
  }

  def deleteDeviceR(ns: Namespace, uuid: Device.Id): Route = {
    completeOrRecoverWith(deleteDevice(ns, uuid)) {
      case MissingDevice =>
        complete(StatusCodes.NotFound ->
          ErrorRepresentation(ErrorCodes.MissingDevice, "Device doesn't exist"))
    }
  }

  def search(ns: Namespace): Route = {
    parameters(('status.?(false), 'regex.?)) { (includeStatus: Boolean, regex: Option[String]) =>
      val resultIO = DeviceSearch.search(ns, regex, includeStatus)
      complete(db.run(resultIO))
    }
  }

  val route =
    (pathPrefix("devices") & extractNamespace) { ns =>
      extractUuid { deviceUuid =>
        pathEnd {
          get {
            fetchDevice(ns, toUUID(deviceUuid))
          } ~
          delete {
            deleteDeviceR(ns, toUUID(deviceUuid))
          }
        }
      } ~
      // TODO: more sensible routes which do not collide with resolver
      (path("search") & get) {
        search(ns)
      } ~
      (path("create") & post & extractDevice(ns)) { device =>
        updateDevice(device)
      }
    }
}
