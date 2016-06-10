package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import org.genivi.sota.common.IDeviceRegistry
import org.genivi.sota.datatype.Namespace._
import org.genivi.sota.data.{Device, DeviceT, Namespaces}
import org.genivi.sota.device_registry.common.Errors._
import org.joda.time.DateTime
import java.util.UUID._

import scala.concurrent.{ExecutionContext, Future}
import Device._

class FakeDeviceRegistry()(implicit system: ActorSystem, mat: ActorMaterializer)
  extends IDeviceRegistry {

  val logger = Logging.getLogger(system, this)

  var devices = Seq.empty[Device]

  override def searchDevice
    (ns: Namespace, re: String Refined Regex)
    (implicit ec: ExecutionContext): Future[Seq[Device]] =
    FastFuture.successful(
      devices
        .filter(_.deviceId.isDefined)
        .filter(d => re.get.r.findFirstIn(d.deviceId.get.underlying).isDefined))

  // TODO: handle conflicts on deviceId
  override def createDevice
    (d: DeviceT)
    (implicit ec: ExecutionContext): Future[Id] = {
    val id: Id = Id(Refined.unsafeApply(randomUUID.toString))
    devices = devices :+ Device(namespace = Namespaces.defaultNs,
                                id = id,
                                deviceName = d.deviceName,
                                deviceId = d.deviceId,
                                deviceType = d.deviceType)
    FastFuture.successful(id)
  }

  override def fetchDevice
    (id: Id)
    (implicit ec: ExecutionContext): Future[Device] =
    devices.find(_.id == id) match {
      case Some(d) => FastFuture.successful(d)
      case None => FastFuture.failed(MissingDevice)
    }

  override def fetchDeviceByDeviceId
    (id: DeviceId)
    (implicit ec: ExecutionContext): Future[Device] =
    devices.find(_.deviceId.contains(id)) match {
      case Some(d) => FastFuture.successful(d)
      case None => FastFuture.failed(MissingDevice)
    }

  // TODO: handle conflicts on deviceId
  override def updateDevice
    (id: Id, device: DeviceT)
    (implicit ec: ExecutionContext): Future[Unit] = {
    fetchDevice(id).map { _ =>
      devices = devices.map { d =>
        if (d.id == id)
          d.copy(deviceName = device.deviceName, deviceId = device.deviceId, deviceType = device.deviceType)
        else d
      }
      FastFuture.successful(())
    }
  }

  override def deleteDevice
    (id: Id)
    (implicit ec: ExecutionContext): Future[Unit] = {
    devices = devices.filter(_.id != id)
    FastFuture.successful(())
  }

  override def updateLastSeen
    (id: Id, seenAt: DateTime = DateTime.now)
    (implicit ec: ExecutionContext): Future[Unit] =
    fetchDevice(id).map { _ =>
      devices = devices.map { d =>
        if (d.id == id)
          d.copy(lastSeen = Option(seenAt))
        else d
      }
    }

}
