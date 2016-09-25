/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.device_registry

import cats.Show
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import io.circe.Json
import io.circe.jawn._

import org.genivi.sota.data.{Device, DeviceT, Namespace, Uuid}
import org.genivi.sota.data.Namespace._
import org.genivi.sota.db.Operators._
import org.genivi.sota.device_registry.common.Errors
import org.genivi.sota.refined.SlickRefined._
import java.time.Instant

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import slick.driver.MySQLDriver.api._


object DeviceRepository {
  import org.genivi.sota.db.SlickExtensions._

  import Device._

  // TODO generalize
  implicit val deviceNameColumnType =
    MappedColumnType.base[DeviceName, String](
      { case DeviceName(value) => value.toString },
      DeviceName(_)
    )

  implicit val deviceIdColumnType =
    MappedColumnType.base[DeviceId, String](
      { case DeviceId(value) => value.toString },
      DeviceId(_)
    )

  // scalastyle:off
  class DeviceTable(tag: Tag) extends Table[Device](tag, "Device") {
    def namespace = column[Namespace]("namespace")
    def uuid = column[Uuid]("uuid")
    def deviceName = column[DeviceName]("device_name")
    def deviceId = column[Option[DeviceId]]("device_id")
    def deviceType = column[DeviceType]("device_type")
    def lastSeen = column[Option[Instant]]("last_seen")

    def * = (namespace, uuid, deviceName, deviceId, deviceType, lastSeen).shaped <>
      ((Device.apply _).tupled, Device.unapply)

    def pk = primaryKey("uuid", uuid)

    def uniqueName = index("Device_unique_device_name", (namespace, deviceName), unique = true)
    def uniqueDeviceId = index("Device_unique_device_id", (namespace, deviceId), unique = true)
  }

  // scalastyle:on
  val devices = TableQuery[DeviceTable]

  def list(ns: Namespace): DBIO[Seq[Device]] = devices.filter(_.namespace === ns).result

  def create(ns: Namespace, device: DeviceT)
            (implicit ec: ExecutionContext): DBIO[Uuid] = {
    val uuid: Uuid = Uuid.generate()

    (devices += Device(ns, uuid, device.deviceName, device.deviceId, device.deviceType))
      .handleIntegrityErrors(Errors.ConflictingDevice)
      .map(_ => uuid)
  }

  def exists(ns: Namespace, uuid: Uuid)
            (implicit ec: ExecutionContext): DBIO[Device] =
    devices
      .filter(d => d.namespace === ns && d.uuid === uuid)
      .result
      .failIfNotSingle(Errors.MissingDevice)

  def findByDeviceId(ns: Namespace, deviceId: DeviceId)
                    (implicit ec: ExecutionContext): DBIO[Seq[Device]] =
    devices
      .filter(d => d.namespace === ns && d.deviceId === deviceId)
      .result

  def search(ns: Namespace, re: String Refined Regex): DBIO[Seq[Device]] =
    devices
      .filter(d => d.namespace === ns && regex(d.deviceName, re))
      .result

  def findByUuid(uuid: Uuid)(implicit ec: ExecutionContext): DBIO[Device] =
    devices
      .filter(_.uuid === uuid)
      .result
      .failIfNotSingle(Errors.MissingDevice)

  def update(uuid: Uuid, device: DeviceT)
            (implicit ec: ExecutionContext): DBIO[Unit] =
    devices
      .filter(_.uuid === uuid)
      .map (d => (d.deviceName, d.deviceId, d.deviceType))
      .update((device.deviceName, device.deviceId, device.deviceType))
      .handleIntegrityErrors(Errors.ConflictingDevice)
      .map (_ => ())

  def updateLastSeen(uuid: Uuid)
                    (implicit ec: ExecutionContext): DBIO[Unit] =
    devices
      .filter (_.uuid === uuid)
      .map (_.lastSeen)
      .update(Some(Instant.now()))
      .map (_ => ())

  def delete(uuid: Uuid)
            (implicit ec: ExecutionContext): DBIO[Unit] = {
    val dbIO = for {
      _ <- devices.filter(_.uuid === uuid).delete
      _ <- SystemInfo.delete(uuid)
    } yield ()

    dbIO.transactionally
  }

}
