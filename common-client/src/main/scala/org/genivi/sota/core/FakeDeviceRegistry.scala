/*
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */

package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import cats.syntax.show._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import io.circe.Json
import java.time.Instant
import java.util.UUID._
import java.util.concurrent.ConcurrentHashMap
import org.genivi.sota.common.{ClientRequest, DeviceRegistry}
import org.genivi.sota.data.Device._
import org.genivi.sota.data.{Device, DeviceT, Namespace, Uuid}
import org.genivi.sota.device_registry.common.Errors._
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}


class FakeDeviceRegistry(namespace: Namespace)
                        (implicit system: ActorSystem, mat: ActorMaterializer)
  extends DeviceRegistry {

  val logger = Logging.getLogger(system, this)

  private val devices =  new ConcurrentHashMap[Uuid, Device]()
  private val systemInfo = new ConcurrentHashMap[Uuid, Json]()

  type Request[Resp] = FutureClientRequest[Resp]
  private def mkReq[Resp](f: Future[Resp]): Request[Resp] = FutureClientRequest(f)

  override def searchDevice
  (ns: Namespace, re: String Refined Regex)
  (implicit ec: ExecutionContext): Request[Seq[Device]] = mkReq {
    FastFuture.successful(
      devices
        .values()
        .asScala
        .filter(_.deviceId.isDefined)
        .filter(d => re.get.r.findFirstIn(d.deviceId.map(_.show).getOrElse("")).isDefined)
        .toSeq
    )
  }

  // TODO: handle conflicts on deviceId
  override def createDevice(d: DeviceT)
                           (implicit ec: ExecutionContext): Request[Uuid] = mkReq {
    val uuid: Uuid = Uuid.generate()
    devices.put(uuid,
      Device(namespace = namespace,
        uuid = uuid,
        deviceName = d.deviceName,
        deviceId = d.deviceId,
        deviceType = d.deviceType))
    FastFuture.successful(uuid)
  }

  override def fetchDevice(uuid: Uuid)
                          (implicit ec: ExecutionContext): Request[Device] = mkReq{
    devices.asScala.get(uuid) match {
      case Some(d) => FastFuture.successful(d)
      case None => FastFuture.failed(MissingDevice)
    }
  }

  override def fetchByDeviceId (ns: Namespace, deviceId: DeviceId)
                               (implicit ec: ExecutionContext): Request[Device] = mkReq {
    devices
      .asScala
      .values
      .find(_.deviceId.contains(deviceId)) match {
      case Some(d) => FastFuture.successful(d)
      case None => FastFuture.failed(MissingDevice)
    }
  }

  // TODO: handle conflicts on deviceId
  override def updateDevice
  (uuid: Uuid, device: DeviceT)
  (implicit ec: ExecutionContext): Request[Unit] = mkReq {
    devices.asScala.get(uuid) match {
      case Some(d) =>
        d.copy(deviceId = device.deviceId, deviceName = device.deviceName, deviceType = device.deviceType)
      case None =>
    }

    Future.successful(())
  }

  override def deleteDevice
  (uuid: Uuid)
  (implicit ec: ExecutionContext): Request[Unit] = mkReq {
    devices.remove(uuid)
    FastFuture.successful(())
  }

  override def updateLastSeen(uuid: Uuid, seenAt: Instant = Instant.now)
  (implicit ec: ExecutionContext): Request[Unit] = mkReq {
    devices.asScala.get(uuid).foreach { d =>
      devices.put(uuid, d.copy(lastSeen = Option(seenAt)))
    }

    FastFuture.successful(())
  }

  override def updateSystemInfo
  (uuid: Uuid, json: Json)
  (implicit ec: ExecutionContext): Request[Unit] = mkReq {
    devices.asScala.get(uuid) match {
      case Some(_) =>
        systemInfo.put(uuid, json)
        FastFuture.successful(())
      case None =>
        FastFuture.failed(MissingDevice)
    }
  }

  override def getSystemInfo
  (uuid: Uuid)
  (implicit ec: ExecutionContext): Request [Json] = mkReq {
    systemInfo.asScala.get(uuid) match {
      case Some(x) => FastFuture.successful(x)
      case None => FastFuture.failed(MissingDevice)
    }
  }

  def addDevice(device: Device): Device = {
    devices.put(device.uuid, device)
  }
}
