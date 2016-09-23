/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.common

import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import io.circe.Json
import java.time.Instant
import org.genivi.sota.data.{Device, DeviceT, Namespace, Uuid}
import scala.concurrent.{ExecutionContext, Future}

import Device._


trait DeviceRegistry {

  type Request[T] <: ClientRequest[T, Request]

  // TODO: Needs namespace
  def searchDevice
    (ns: Namespace, re: String Refined Regex)
    (implicit ec: ExecutionContext): Request[Seq[Device]]

  def listNamespace(ns: Namespace)
  (implicit ec: ExecutionContext): Request[Seq[Device]] =
    searchDevice(ns, Refined.unsafeApply(".*"))

  def createDevice
  (device: DeviceT)
  (implicit ec: ExecutionContext): Request[Uuid]

  def fetchDevice
    (uuid: Uuid)
    (implicit ec: ExecutionContext): Request[Device]

  def fetchByDeviceId
    (ns: Namespace, deviceId: DeviceId)
    (implicit ec: ExecutionContext): Request[Device]

  def updateDevice
    (uuid: Uuid, device: DeviceT)
    (implicit ec: ExecutionContext): Request[Unit]

  def deleteDevice
    (uuid: Uuid)
    (implicit ec: ExecutionContext): Request[Unit]

  def updateLastSeen
    (uuid: Uuid, seenAt: Instant = Instant.now)
    (implicit ec: ExecutionContext): Request[Unit]

  def updateSystemInfo
    (uuid: Uuid, json: Json)
    (implicit ec: ExecutionContext): Request[Unit]

  def getSystemInfo
    (uuid: Uuid)
    (implicit ec: ExecutionContext): Request[Json]
}
