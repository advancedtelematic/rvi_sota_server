/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.db

import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.Device
import org.genivi.sota.db.Operators.regex
import org.joda.time.DateTime
import scala.concurrent.ExecutionContext
import slick.driver.MySQLDriver.api._
import slick.lifted.TableQuery


/**
 * Database mapping definition for the Devices table.
 * The Devices table is simple: it only contains the list of the devices
 * (Devices) that are known to the SOTA system.  Other tables that refer to
 * Devices should have a foreign key into this table
 */
object Devices {
  import org.genivi.sota.refined.SlickRefined._
  import org.genivi.sota.db.SlickExtensions._

  /**
   * Slick mapping definition for the Device table
   * @see {@link http://slick.typesafe.com/}
   */
  // scalastyle:off
  class DeviceTable(tag: Tag) extends Table[Device](tag, "Device") {
    def namespace = column[Namespace]("namespace")
    def uuid = column[Device.Id]("uuid")
    def deviceId = column[Device.DeviceId]("device_id")
    def deviceType = column[Device.DeviceType]("device_type")
    def lastSeen = column[Option[DateTime]]("last_seen")

    def pk = primaryKey("uuid", (namespace, uuid))  // insertOrUpdate doesn't work if
                                                  // we use O.PrimaryKey in the uuid
                                                  // column, see Slick issue #966.

    def * = (namespace, uuid, deviceId, deviceType, lastSeen) <> (Device.tupled, Device.unapply)
  }
  // scalastyle:on

  /**
   * Internal helper definition to accesss the SQL table
   */
  val devices = TableQuery[DeviceTable]

  /**
   * List all the Devices that are known to the system
   * @return A list of Devices
   */
  def list(): DBIO[Seq[Device]] = devices.result


  def all(): TableQuery[DeviceTable] = devices

  /**
   * Check if a Device exists
   * @param device The namespaced Device to search for
   * @return Option.Some if the device is present. Option.None if it is absent
   */
  def exists(ns: Namespace, uuid: Device.Id): DBIO[Option[Device]] =
    devices
      .filter(d => d.namespace === ns && d.uuid === uuid)
      .result
      .headOption

  /**
   * Add a new Device to SOTA.
   * @param device The Device of the device
   */
  def create(device: Device)(implicit ec: ExecutionContext) : DBIO[Device] =
    devices.insertOrUpdate(device).map(_ => device)

  /**
   * Delete a Device from SOTA.
   * Note that this doesn't perform a cascading delete.  You should delete any
   * objects that reference this device first.
   * @param device The Device to remove
   */
  def deleteById(ns: Namespace, uuid: Device.Id) : DBIO[Int] =
    devices.filter(d => d.namespace === ns && d.uuid === uuid).delete

  /**
   * Find Devices that match a regular expression
   * @param reg A regular expression
   * @return A list of matching Devices
   */
  def searchByRegex(reg:String): Query[DeviceTable, Device, Seq] =
    devices.filter(d => regex(d.uuid, reg))

  def updateLastSeen(ns: Namespace, uuid: Device.Id, lastSeen: DateTime = DateTime.now)
                    (implicit ec: ExecutionContext): DBIO[DateTime] = {
    devices
      .filter(d => d.namespace === ns && d.uuid === uuid)
      .map(_.lastSeen)
      .update(Some(lastSeen))
      .map(_ => lastSeen)
  }

  def findBy(ns: Namespace, uuid: Device.Id): DBIO[Device] = {
    devices
      .filter(d => d.namespace === ns && d.uuid === uuid)
      .result
      .head
  }

  def findByDeviceId(ns: Namespace, deviceId: Device.DeviceId): DBIO[Device] = {
    devices
      .filter(d => d.namespace === ns && d.deviceId === deviceId)
      .result
      .head
  }
}
