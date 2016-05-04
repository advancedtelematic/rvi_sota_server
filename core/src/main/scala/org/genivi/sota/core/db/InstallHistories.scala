/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.db

import org.genivi.sota.core.data.InstallHistory
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.{PackageId, Device}
import org.joda.time.DateTime
import slick.driver.MySQLDriver.api._


/**
 * Database mapping definition for the InstallHistory table.
 * This provides a history of update installs that have been attempted on a
 * VIN. It records the identity of the update, thi VIN, the time of the attempt
 * and whether the install was successful
 */
object InstallHistories {

  import org.genivi.sota.db.SlickExtensions._
  import org.genivi.sota.refined.SlickRefined._

  /**
   * Slick mapping definition for the InstallHistory table
   * @see [[http://slick.typesafe.com/]]
   */
  // scalastyle:off
  class InstallHistoryTable(tag: Tag) extends Table[InstallHistory](tag, "InstallHistory") {

    def id             = column[Long]             ("id", O.PrimaryKey, O.AutoInc)
    def namespace      = column[Namespace]        ("namespace")
    def deviceUuid     = column[Device.Id]        ("device_uuid")
    def updateId       = column[java.util.UUID]   ("update_request_id")
    def packageName    = column[PackageId.Name]   ("packageName")
    def packageVersion = column[PackageId.Version]("packageVersion")
    def success        = column[Boolean]          ("success")
    def completionTime = column[DateTime]         ("completionTime")

    def * = (id.?, namespace, deviceUuid, updateId, packageName, packageVersion, success, completionTime).shaped <>
      (r => InstallHistory(r._1, r._2, r._3, r._4, PackageId(r._5, r._6), r._7, r._8),
        (h: InstallHistory) =>
          Some((h.id, h.namespace, h.deviceUuid, h.updateId, h.packageId.name, h.packageId.version, h.success, h.completionTime)))
  }
  // scalastyle:on

  /**
   * Internal helper definition to accesss the SQL table
   */
  private val installHistories = TableQuery[InstallHistoryTable]

  /**
   * List the install attempts that have been made on a specific VIN
   * This information is fetched from the InstallHistory SQL table.
   *
   * @param deviceUuid The VIN to fetch data for
   * @return A list of the install history for that VIN
   */
  def list(ns: Namespace, deviceUuid: Device.Id): DBIO[Seq[InstallHistory]] =
    installHistories.filter(i => i.namespace === ns && i.deviceUuid === deviceUuid).result

  /**
   * Record the outcome of a install attempt on a specific VIN. The result of
   * the install is returned from the SOTA client via RVI.
   *
   * @param deviceUuid The VIN that the install attempt ran on
   * @param updateId The Id of the update that was attempted to be installed
   * @param success Whether the install was successful
   */
  def log(ns: Namespace, deviceUuid: Device.Id, updateId: java.util.UUID,
          packageId: PackageId, success: Boolean): DBIO[Int] = {
    installHistories += InstallHistory(None, ns, deviceUuid, updateId, packageId, success, DateTime.now)
  }

}
