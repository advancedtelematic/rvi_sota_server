/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.device_registry

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import io.circe.generic.auto._
import org.genivi.sota.data.{Device, DeviceT}
import org.genivi.sota.datatype.Namespace._
import org.genivi.sota.device_registry.common.DeviceRegistryErrors
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.marshalling.RefinedMarshallingSupport._
import org.genivi.sota.rest.Validation._
import org.joda.time._
import scala.concurrent.ExecutionContext
import slick.driver.MySQLDriver.api._


/**
 * API routes for device registry.
 *
 * @see {@linktourl http://pdxostc.github.io/rvi_sota_server/dev/api.html}
 */
class Routes(namespaceExtractor: Directive1[Namespace])
            (implicit system: ActorSystem,
             db: Database,
             mat: ActorMaterializer,
             ec: ExecutionContext) {

  import Device._
  import Directives._
  import DeviceRegistryErrors._
  import StatusCodes._

  val extractId: Directive1[Id] = refined[ValidId](Slash ~ Segment).map(Id(_))
  val extractDeviceId: Directive1[DeviceId] = parameter('deviceId.as[String]).map(DeviceId(_))

  def searchDevice: Route =
    parameters(('regex.as[String Refined Regex].?,
      'namespace.as[Namespace],
      'deviceName.as[String].?,
      'deviceId.as[String].?)) { // TODO: Use refined
      case (Some(re), ns, None, None) =>
        complete(db.run(Devices.search(ns, re)))
      case (None, ns, Some(name), None) =>
        complete(db.run(Devices.findByDeviceName(ns, DeviceName(name))))
      case (None, ns, None, Some(id)) =>
        complete(db.run(Devices.findByDeviceId(ns, DeviceId(id))))
      case (None, ns, None, None) => complete(db.run(Devices.list))
      case _ =>
        complete((BadRequest, "'regex', 'deviceName' and 'deviceId' parameters cannot be used together!"))
    }

  def createDevice(ns: Namespace, device: DeviceT): Route = {
    val f = db.run(Devices.create(ns, device)).map(id => Created -> id)
    complete(f)
  }

  def fetchDevice(ns: Namespace, id: Id): Route =
    complete(db.run(Devices.exists(ns, id)))

  def updateDevice(ns: Namespace, id: Id, device: DeviceT): Route =
    complete(db.run(Devices.update(ns, id, device)))

  def deleteDevice(ns: Namespace, id: Id): Route =
    complete(db.run(Devices.delete(ns, id)))

  def updateLastSeen(ns: Namespace, id: Id): Route =
    complete(db.run(Devices.updateLastSeen(ns, id)))

  def api: Route =
    pathPrefix("devices") {
      (get & pathEnd) {
        searchDevice
      } ~
        namespaceExtractor { (ns: Namespace) =>
          (post & entity(as[DeviceT]) & pathEndOrSingleSlash) { (device: DeviceT) =>
            createDevice(ns, device)
          } ~
            extractId { (id: Id) =>
              (get & pathEnd) {
                fetchDevice(ns, id)
              } ~
                (put & entity(as[DeviceT]) & pathEnd) { (device: DeviceT) =>
                  updateDevice(ns, id, device)
                } ~
                (delete & pathEnd) {
                  deleteDevice(ns, id)
                } ~
                (post & path("ping")) {
                  updateLastSeen(ns, id)
                }
            }
        }
    }


  /**
   * Base API route for devices.
   *
   * @return      Route object containing routes for creating, deleting, and listing devices
   * @throws      MissingDevice if device doesn't exist
   */
  def route: Route = {
    api
  }

}
