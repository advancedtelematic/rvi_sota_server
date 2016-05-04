/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.db

import java.util.UUID

import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Uuid
import org.genivi.sota.core.data.{Package, UpdateSpec, UpdateStatus}
import org.genivi.sota.core.db.UpdateRequests.UpdateRequestTable
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.{PackageId, Device}
import org.genivi.sota.db.SlickExtensions
import slick.driver.MySQLDriver.api._

import scala.concurrent.ExecutionContext

/**
 * Database mapping definition for the UpdateSpecs and RequiredPackages tables.
 * UpdateSpecs records the status of a package update for a specific VIN. One
 * UpdateSpecs row has several RequiredPackages rows that detail the individual
 * packages that need to be installed.
 */
object UpdateSpecs {

  import SlickExtensions._
  import UpdateStatus._
  import org.genivi.sota.refined.SlickRefined._

  implicit val UpdateStatusColumn = MappedColumnType.base[UpdateStatus, String](_.value.toString, UpdateStatus.withName)

  /**
   * Slick mapping definition for the UpdateSpecs table
   * @see [[http://slick.typesafe.com/]]
   */
  class UpdateSpecTable(tag: Tag)
      extends Table[(Namespace, UUID, Device.Id, UpdateStatus)](tag, "UpdateSpec") {
    def namespace = column[Namespace]("namespace")
    def requestId = column[UUID]("update_request_id")
    def deviceUuid = column[Device.Id]("device_uuid")
    def status = column[UpdateStatus]("status")

    def pk = primaryKey("pk_update_specs", (requestId, deviceUuid))

    def * = (namespace, requestId, deviceUuid, status)
  }

  /**
   * Slick mapping definition for the RequiredPackage table
   * @see {@link http://slick.typesafe.com/}
   */
  class RequiredPackageTable(tag: Tag)
      extends Table[(Namespace, UUID, Device.Id, PackageId.Name, PackageId.Version)](tag, "RequiredPackage") {
    def namespace = column[Namespace]("namespace")
    def requestId = column[UUID]("update_request_id")
    def deviceUuid = column[Device.Id]("device_uuid")
    def packageName = column[PackageId.Name]("package_name")
    def packageVersion = column[PackageId.Version]("package_version")

    def pk = primaryKey("pk_downloads", (namespace, requestId, deviceUuid, packageName, packageVersion))

    def * = (namespace, requestId, deviceUuid, packageName, packageVersion)
  }

  /**
   * Internal helper definition to accesss the UpdateSpec table
   */
  val updateSpecs = TableQuery[UpdateSpecTable]

  /**
   * Internal helper definition to accesss the RequiredPackages table
   */
  val requiredPackages = TableQuery[RequiredPackageTable]

  /**
   * Internal helper definition to accesss the UpdateRequest table
   */
  val updateRequests = TableQuery[UpdateRequestTable]

  /**
   * Add an update for a specific VIN.
   * This update will consist of one-or-more packages that need to be installed
   * on a single VIN
   * @param updateSpec The list of packages that should be installed
   */
  def persist(updateSpec: UpdateSpec) : DBIO[Unit] = {
    val specProjection = (updateSpec.namespace, updateSpec.request.id, updateSpec.deviceUuid,  updateSpec.status)

    def dependencyProjection(p: Package) =
      // TODO: we're taking the namespace of the update spec, not necessarily the namespace of the package!
      (updateSpec.namespace, updateSpec.request.id, updateSpec.deviceUuid, p.id.name, p.id.version)

    DBIO.seq(
      updateSpecs += specProjection,
      requiredPackages ++= updateSpec.dependencies.map( dependencyProjection )
    )
  }

  /**
   * Install a list of specific packages on a VIN
   * @param deviceUuid The VIN to install on
   * @param updateId Update Id of the update to install
   */
  def load(deviceUuid: Device.Id, updateId: UUID)
          (implicit ec: ExecutionContext) : DBIO[Iterable[UpdateSpec]] = {
    val q = for {
      r  <- updateRequests if (r.id === updateId)
      s  <- updateSpecs if (s.deviceUuid === deviceUuid && s.namespace === r.namespace && s.requestId === r.id)
      rp <- requiredPackages if (rp.deviceUuid === deviceUuid && rp.namespace === r.namespace && rp.requestId === s.requestId)
      p  <- Packages.packages if (p.namespace === r.namespace &&
                                  p.name === rp.packageName &&
                                  p.version === rp.packageVersion)
    } yield (r, s.deviceUuid, s.status, p)
    q.result.map( _.groupBy(x => (x._1, x._2, x._3) ).map {
      case ((request, deviceUuid, status), xs) => UpdateSpec(request.namespace, request, deviceUuid, status, xs.map(_._4).toSet)
    })
  }

  /**
   * Records the status of an update on a specific VIN
   * @param spec The combination of VIN and update request to record the status of
   * @param newStatus The latest status of the installation. One of Pending
   *                  InFlight, Canceled, Failed or Finished.
   */
  def setStatus( spec: UpdateSpec, newStatus: UpdateStatus ) : DBIO[Int] = {
    updateSpecs.filter(t => t.namespace === spec.namespace && t.deviceUuid === spec.deviceUuid && t.requestId === spec.request.id)
      .map( _.status )
      .update( newStatus )
  }

  /**
<<<<<<< HEAD
=======
   * Get the packages that are queued for installation on a VIN.
   * @param deviceUuid The VIN to query
   * @return A List of package names + versions that are due to be installed.
   */
  def getPackagesQueuedForDevice(ns: Namespace, deviceUuid: Device.Id)
                                (implicit ec: ExecutionContext) : DBIO[Iterable[PackageId]] = {
    val specs = updateSpecs.filter(r => r.namespace === ns && r.deviceUuid === deviceUuid &&
      (r.status === UpdateStatus.InFlight || r.status === UpdateStatus.Pending))
    val q = for {
      s <- specs
      u <- updateRequests if s.requestId === u.id
    } yield (u.packageName, u.packageVersion)
    q.result.map(_.map {
      case (packageName, packageVersion) => PackageId(packageName, packageVersion)
    })
  }

  /**
>>>>>>> 8d84ae8... vehicles->devices
   * Return a list of all the VINs that a specific version of a package will be
   * installed on.  Note that VINs where the package has started installation,
   * or has either been installed or where the install failed are not included.
   * @param pkgName The package name to search for
   * @param pkgVer The version of the package to search for
   * @return A list of VINs that the package will be installed on
   */
  def getDevicesQueuedForPackage(ns: Namespace, pkgName: PackageId.Name, pkgVer: PackageId.Version) :
    DBIO[Seq[Device.Id]] = {
    val specs = updateSpecs.filter(r => r.namespace === ns && r.status === UpdateStatus.Pending)
    val q = for {
      s <- specs
      u <- updateRequests if (s.requestId === u.id) && (u.packageName === pkgName) && (u.packageVersion === pkgVer)
    } yield s.deviceUuid
    q.result
  }

  /**
   * Return the status of a specific update
   * @return The update status
   */
  def listUpdatesById(uuid: Refined[String, Uuid]): DBIO[Seq[(Namespace, UUID, Device.Id, UpdateStatus)]] =
    updateSpecs.filter(s => s.requestId === UUID.fromString(uuid.get)).result

  /**
   * Delete all the updates for a specific VIN
   * This is part of the process for deleting a VIN from the system
   * @param device The device to get the VIN to delete from
   */
  def deleteUpdateSpecByDevice(ns: Namespace, uuid: Device.Id) : DBIO[Int] =
    updateSpecs.filter(s => s.namespace === ns && s.deviceUuid === uuid).delete

  /**
   * Delete all the required packages that are needed for a VIN.
   * This is part of the process for deleting a VIN from the system
   * @param device The device to get the VIN to delete from
   */
  def deleteRequiredPackageByDevice(ns: Namespace, uuid: Device.Id) : DBIO[Int] =
    requiredPackages.filter(rp => rp.namespace === ns && rp.deviceUuid === uuid).delete
}
