/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.device_registry

import cats.syntax.show._
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import akka.stream.ActorMaterializer
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import io.circe.Json
import io.circe.generic.auto._
import org.genivi.sota.data.{Device, DeviceT, GroupInfo, Namespace, Uuid}
import org.genivi.sota.device_registry.common.Errors
import org.genivi.sota.http.ErrorHandler
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.marshalling.RefinedMarshallingSupport._
import org.genivi.sota.messaging.MessageBusPublisher
import org.genivi.sota.messaging.Messages.{DeviceCreated, DeviceDeleted}
import org.genivi.sota.rest.Validation._
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import slick.driver.MySQLDriver.api._

class Routes(namespaceExtractor: Directive1[Namespace],
             messageBus: MessageBusPublisher)
            (implicit system: ActorSystem,
             db: Database,
             mat: ActorMaterializer,
             ec: ExecutionContext) {

  import Device._
  import Directives._
  import GroupInfo.Name
  import StatusCodes._

  val logger = LoggerFactory.getLogger(this.getClass)

  def extractUuid(ns: Namespace): Directive1[Uuid] =
    refined[Uuid.Valid](Slash ~ Segment).map(Uuid(_)).flatMap { id =>
      extractExecutionContext.flatMap { implicit ec =>
        val f = db.run(DeviceRepository.exists(ns, id))

        onSuccess(f).flatMap{ _ => provide(id) }
      }
    }

  val extractDeviceId: Directive1[DeviceId] = parameter('deviceId.as[String]).map(DeviceId)
  val extractGroupName: Directive1[GroupInfo.Name] =
    refined[GroupInfo.ValidName](Slash ~ Segment)

  def searchDevice(ns: Namespace): Route =
    parameters(('regex.as[String Refined Regex].?,
                'deviceId.as[String].?)) { // TODO: Use refined
      case (Some(re), None) =>
        complete(db.run(DeviceRepository.search(ns, re)))
      case (None, Some(deviceId)) =>
        complete(db.run(DeviceRepository.findByDeviceId(ns, DeviceId(deviceId))))
      case (None, None) => complete(db.run(DeviceRepository.list(ns)))
      case _ =>
        complete((BadRequest, "'regex' and 'deviceId' parameters cannot be used together!"))
    }

  def createDevice(ns: Namespace, device: DeviceT): Route = {
    val f = db
      .run(DeviceRepository.create(ns, device))
      .andThen {
        case scala.util.Success(uuid) =>
          messageBus.publish(DeviceCreated(ns, uuid, device.deviceName, device.deviceId, device.deviceType))
      }

   onSuccess(f) { uuid =>
     respondWithHeaders(List(Location(Uri("/devices/" + uuid.show)))) {
        complete(Created -> uuid)
     }
   }
  }

  def fetchSystemInfo(uuid: Uuid): Route =
    complete(db.run(SystemInfo.findByUuid(uuid)))

  def createSystemInfo(uuid: Uuid, data: Json): Route =
    complete(Created -> db.run(SystemInfo.create(uuid, data)))

  def updateSystemInfo(uuid: Uuid, data: Json): Route =
    complete(db.run(SystemInfo.update(uuid, data)))

  def listGroups(ns: Namespace): Route =
    complete(db.run(GroupInfoRepository.list(ns)))

  def fetchGroupInfo(groupName: Name, ns: Namespace): Route =
    complete(db.run(GroupInfoRepository.findByName(groupName, ns)))

  def createGroupInfo(groupName: Name, namespace: Namespace, data: Json): Route =
    complete(Created -> db.run(GroupInfoRepository.create(groupName, namespace, data)))

  def updateGroupInfo(groupName: Name, namespace: Namespace, data: Json): Route =
    complete(db.run(GroupInfoRepository.update(groupName, namespace, data)))

  def deleteGroupInfo(groupName: Name, namespace: Namespace): Route =
    complete(db.run(GroupInfoRepository.delete(groupName, namespace)))

  def fetchDevice(uuid: Uuid): Route =
    complete(db.run(DeviceRepository.findByUuid(uuid)))

  def updateDevice(uuid: Uuid, device: DeviceT): Route =
    complete(db.run(DeviceRepository.update(uuid, device)))

  def deleteDevice(ns: Namespace, uuid: Uuid): Route = {
    val f = db
      .run(DeviceRepository.delete(uuid))
      .andThen {
        case scala.util.Success(_) =>
          messageBus.publish(DeviceDeleted(ns, uuid))
      }
    complete(f)
  }

  def updateLastSeen(uuid: Uuid): Route =
    complete(db.run(DeviceRepository.updateLastSeen(uuid)))

  implicit val NamespaceUnmarshaller: FromStringUnmarshaller[Namespace] = Unmarshaller.strict(Namespace.apply)

  def api: Route =
    ErrorHandler.handleErrors {
      (pathPrefix("devices") & namespaceExtractor) { ns =>
        (get & path("group_info") & pathEnd) {
          listGroups(ns)
        } ~
        (extractGroupName & path("group_info") & pathEnd) { groupName =>
           {
            get {
              fetchGroupInfo(groupName, ns)
            } ~
            post {
              entity(as[Json]) { body => createGroupInfo(groupName, ns, body) }
            } ~
            put {
              entity(as[Json]) { body => updateGroupInfo(groupName, ns, body) }
            } ~
            delete {
              deleteGroupInfo(groupName, ns)
            }
          }
        } ~
        (post & entity(as[DeviceT]) & pathEndOrSingleSlash) { device => createDevice(ns, device) } ~
        (get & pathEnd) { searchDevice(ns) } ~
        extractUuid(ns) { uuid =>
          (put & entity(as[DeviceT]) & pathEnd) { device =>
            updateDevice(uuid, device)
          } ~
          (delete & pathEnd) {
            deleteDevice(ns, uuid)
          } ~
          (post & path("ping")) {
            updateLastSeen(uuid)
          } ~
          (path("system_info") & pathEnd) {
            get { fetchSystemInfo(uuid) } ~
            post{ entity(as[Json]) {body => createSystemInfo(uuid, body)} } ~
            put { entity(as[Json]) {body => updateSystemInfo(uuid, body)} }
          } ~
          (get & pathEnd) {
            fetchDevice(uuid)
          }
        }
      }
    }

  /**
   * Base API route for devices.
   *
   * @return      Route object containing routes for creating, deleting, and listing devices
   * @throws      Errors.MissingDevice if device doesn't exist
   */
  def route: Route = api

}
