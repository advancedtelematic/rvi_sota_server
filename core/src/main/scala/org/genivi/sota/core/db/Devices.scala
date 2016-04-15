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
 * (VINs) that are known to the SOTA system.  Other tables that refer to
 * VINs should have a foreign key into this table
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
    def lastSeen = column[Option[DateTime]]("last_seen")

    def pk = primaryKey("uuid", (namespace, uuid))  // insertOrUpdate doesn't work if
                                                  // we use O.PrimaryKey in the uuid
                                                  // column, see Slick issue #966.

    def * = (namespace, uuid, lastSeen) <> (Device.tupled, Device.unapply)
  }
  // scalastyle:on

  /**
   * Internal helper definition to accesss the SQL table
   */
  val devices = TableQuery[DeviceTable]

  /**
   * List all the VINs that are known to the system
   * @return A list of Devices
   */
  def list(): DBIO[Seq[Device]] = devices.result


  def all(): TableQuery[DeviceTable] = devices

  /**
   * Check if a VIN exists
   * @param device The namespaced VIN to search for
   * @return Option.Some if the device is present. Option.None if it is absent
   */
  def exists(device: Device): DBIO[Option[Device]] =
    devices
      .filter(d => d.namespace === device.namespace && d.uuid === device.uuid)
      .result
      .headOption

  /**
   * Add a new VIN to SOTA.
   * @param device The VIN of the device
   */
  def create(device: Device)(implicit ec: ExecutionContext) : DBIO[Device] =
    devices.insertOrUpdate(device).map(_ => device)

  /**
   * Delete a VIN from SOTA.
   * Note that this doesn't perform a cascading delete.  You should delete any
   * objects that reference this device first.
   * @param device The VIN to remove
   */
  def deleteById(device : Device) : DBIO[Int] =
    devices.filter(d => d.namespace === device.namespace && d.uuid === device.uuid).delete

  /**
   * Find VINs that match a regular expression
   * @param reg A regular expression
   * @return A list of matching VINs
   */
  def searchByRegex(reg:String): Query[DeviceTable, Device, Seq] =
    devices.filter(d => regex(d.uuid, reg))

  def updateLastSeen(device: Device, lastSeen: DateTime = DateTime.now)
                    (implicit ec: ExecutionContext): DBIO[DateTime] = {
    devices
      .filter(d => d.namespace === device.namespace && d.uuid === device.uuid)
      .map(_.lastSeen)
      .update(Some(lastSeen))
      .map(_ => lastSeen)
  }

  def findBy(device: Device): DBIO[Device] = {
    devices
      .filter(d => d.namespace === device.namespace && d.uuid === device.uuid)
      .result
      .head
  }
}
