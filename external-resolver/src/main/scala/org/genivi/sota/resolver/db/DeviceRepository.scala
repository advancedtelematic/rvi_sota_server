/*
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.db

import akka.stream.ActorMaterializer
import eu.timepit.refined.api.Refined
import eu.timepit.refined.refineV
import eu.timepit.refined.string.Regex
import org.genivi.sota.common.DeviceRegistry
import org.genivi.sota.data.{Device, Namespace, PackageId, Uuid}
import org.genivi.sota.db.Operators._
import org.genivi.sota.resolver.common.Errors
import org.genivi.sota.resolver.components.{Component, ComponentRepository}
import org.genivi.sota.resolver.data.Firmware
import org.genivi.sota.resolver.db.PackageIdDatabaseConversions._
import org.genivi.sota.resolver.filters._
import slick.driver.MySQLDriver.api._

import scala.concurrent.{ExecutionContext, Future}


object DeviceRepository {
  import org.genivi.sota.db.SlickExtensions._
  import org.genivi.sota.refined.SlickRefined._

  class InstalledFirmwareTable(tag: Tag) extends Table[(Firmware, Uuid)](tag, "Firmware") {
    def namespace     = column[Namespace]           ("namespace")
    def module        = column[Firmware.Module]     ("module")
    def firmware_id   = column[Firmware.FirmwareId] ("firmware_id")
    def last_modified = column[Long]                ("last_modified")
    def device        = column[Uuid]                ("device_uuid")

    // insertOrUpdate buggy for composite-keys, see Slick issue #966.
    def pk = primaryKey("pk_installedFirmware", (namespace, module, firmware_id, device))

    def * = (namespace, module, firmware_id, last_modified, device).shaped <>
      (p => (Firmware(p._1, p._2, p._3, p._4), p._5),
        (fw: (Firmware, Uuid)) =>
          Some((fw._1.namespace, fw._1.module, fw._1.firmwareId, fw._1.lastModified, fw._2)))
  }
  // scalastyle:on

  val installedFirmware = TableQuery[InstalledFirmwareTable]

  def firmwareExists(namespace: Namespace, module: Firmware.Module)
                    (implicit ec: ExecutionContext): DBIO[Firmware.Module] = {
    val res = for {
      ifw <- installedFirmware.filter(i => i.namespace === namespace && i.module === module).result.headOption
    } yield ifw
    res.flatMap(_.fold[DBIO[Firmware.Module]]
      (DBIO.failed(Errors.MissingFirmware))(x => DBIO.successful(x._1.module)))
  }

  def installFirmware
  (namespace: Namespace, module: Firmware.Module, firmware_id: Firmware.FirmwareId,
   last_modified: Long, device: Uuid)
  (implicit ec: ExecutionContext): DBIO[Unit] = {
    for {
      _ <- firmwareExists(namespace, module)
      _ <- installedFirmware.insertOrUpdate((Firmware(namespace, module, firmware_id, last_modified), device))
    } yield()
  }

  def firmwareOnDevice
  (namespace: Namespace, deviceId: Uuid)
  (implicit ec: ExecutionContext): DBIO[Seq[Firmware]] = {
    installedFirmware
      .filter(_.namespace === namespace)
      .filter(_.device === deviceId)
      .result
      .map(_.map(_._1))
  }

  //This method is only intended to be called when the client reports installed firmware.
  //It therefore clears all installed firmware for the given vin and replaces with the reported
  //state instead.
  def updateInstalledFirmware(device: Uuid, firmware: Set[Firmware])
                             (implicit ec: ExecutionContext): DBIO[Unit] = {
    (for {
      _       <- installedFirmware.filter(_.device === device).delete
      _       <- installedFirmware ++= firmware.map(fw =>
        (Firmware(fw.namespace, fw.module, fw.firmwareId, fw.lastModified), device))
    } yield ()).transactionally
  }

  /*
   * Installed packages.
   */

  // scalastyle:off
  class InstalledPackageTable(tag: Tag) extends Table[(Namespace, Uuid, PackageId)](tag, "InstalledPackage") {
    def device         = column[Uuid]("device_uuid")
    def namespace      = column[Namespace]("namespace")
    def packageName    = column[PackageId.Name]("packageName")
    def packageVersion = column[PackageId.Version]("packageVersion")

    // insertOrUpdate buggy for composite-keys, see Slick issue #966.
    def pk = primaryKey("pk_installedPackage", (namespace, device, packageName, packageVersion))

    def * = (namespace, device, packageName, packageVersion).shaped <>
      (p => (p._1, p._2, PackageId(p._3, p._4)),
        (vp: (Namespace, Uuid, PackageId)) => Some((vp._1, vp._2, vp._3.name, vp._3.version)))
  }
  // scalastyle:on

  val installedPackages = TableQuery[InstalledPackageTable]

  def installPackage
  (namespace: Namespace, device: Uuid, pkgId: PackageId)
  (implicit ec: ExecutionContext): DBIO[Unit] =
    for {
      _ <- PackageRepository.exists(namespace, pkgId)
      _ <- installedPackages.insertOrUpdate((namespace, device, pkgId))
    } yield ()

  def uninstallPackage
  (namespace: Namespace, device: Uuid, pkgId: PackageId)
  (implicit ec: ExecutionContext): DBIO[Unit] =
    for {
      _ <- PackageRepository.exists(namespace, pkgId)
      _ <- installedPackages.filter {ip =>
        ip.namespace === namespace &&
          ip.device === device &&
          ip.packageName === pkgId.name &&
          ip.packageVersion === pkgId.version
      }.delete
    } yield ()

  def updateInstalledPackages(namespace: Namespace, device: Uuid, packages: Set[PackageId] )
                             (implicit ec: ExecutionContext): DBIO[Unit] = {
    def filterAvailablePackages(ids: Set[PackageId]) : DBIO[Set[PackageId]] =
      PackageRepository.load(namespace, ids).map(_.map(_.id))

    def helper(newPackages: Set[PackageId], deletedPackages: Set[PackageId] )
              (implicit ec: ExecutionContext) : DBIO[Unit] = DBIO.seq(
      installedPackages.filter( ip =>
        ip.device === device &&
          (ip.packageName.mappedTo[String] ++ ip.packageVersion.mappedTo[String])
            .inSet( deletedPackages.map( id => id.name.get + id.version.get ))
      ).delete,
      installedPackages ++= newPackages.map((namespace, device, _))
    ).transactionally

    for {
      installedPackages <- DeviceRepository.installedOn(device)
      newPackages       =  packages -- installedPackages
      deletedPackages   =  installedPackages -- packages
      newAvailablePackages <- filterAvailablePackages(newPackages)
      _ <- helper(newAvailablePackages, deletedPackages)
    } yield ()
  }

  def installedOn(device: Uuid, regexFilter: Option[String] = None)
                 (implicit ec: ExecutionContext) : DBIO[Set[PackageId]] = {
    installedPackages
      .filter(_.device === device)
      .regexFilter(regexFilter)(_.packageName, _.packageVersion)
      .result.map(_.map(_._3).toSet)
  }

  def listInstalledPackages: DBIO[Seq[(Namespace, Uuid, PackageId)]] =
    installedPackages.result
  // TODO: namespaces?

  def deleteInstalledPackageById(namespace: Namespace, device: Uuid): DBIO[Int] =
    installedPackages.filter(i => i.namespace === namespace && i.device === device).delete

  /*
   * Installed components.
   */

  // scalastyle:off
  class InstalledComponentTable(tag: Tag)
    extends Table[(Namespace, Uuid, Component.PartNumber)](tag, "InstalledComponent") {

    def namespace = column[Namespace]("namespace")
    def device = column[Uuid]("device_uuid")
    def partNumber = column[Component.PartNumber]("partNumber")

    // insertOrUpdate buggy for composite-keys, see Slick issue #966.
    def pk = primaryKey("pk_installedComponent", (namespace, device, partNumber))

    def * = (namespace, device, partNumber)
  }
  // scalastyle:on

  val installedComponents = TableQuery[InstalledComponentTable]

  def listInstalledComponents: DBIO[Seq[(Namespace, Uuid, Component.PartNumber)]] =
    installedComponents.result

  def deleteInstalledComponentById(namespace: Namespace, device: Uuid): DBIO[Int] =
    installedComponents.filter(i => i.namespace === namespace && i.device === device).delete

  def installComponent(namespace: Namespace, device: Uuid, part: Component.PartNumber)
                      (implicit ec: ExecutionContext): DBIO[Unit] =
    for {
      _ <- ComponentRepository.exists(namespace, part)
      _ <- installedComponents += ((namespace, device, part))
    } yield ()

  def uninstallComponent
  (namespace: Namespace, device: Uuid, part: Component.PartNumber)
  (implicit ec: ExecutionContext): DBIO[Unit] =
    for {
      _ <- ComponentRepository.exists(namespace, part)
      _ <- installedComponents.filter { ic =>
        ic.namespace  === namespace &&
          ic.device === device &&
          ic.partNumber === part
      }.delete
    } yield ()

  def componentsOnDevice(namespace: Namespace, device: Uuid)
                        (implicit ec: ExecutionContext): DBIO[Seq[Component.PartNumber]] = {
    installedComponents
      .filter(_.namespace === namespace)
      .filter(_.device === device)
      .map(_.partNumber)
      .result
  }

  def search(namespace : Namespace,
             re        : Option[Refined[String, Regex]],
             pkgName   : Option[PackageId.Name],
             pkgVersion: Option[PackageId.Version],
             part      : Option[Component.PartNumber],
             deviceRegistry: DeviceRegistry)
            (implicit db: Database, ec: ExecutionContext, mat: ActorMaterializer): Future[Seq[Uuid]] = {
    def toRegex[T](r: Refined[String, T]): Refined[String, Regex] =
      refineV[Regex](r.get).right.getOrElse(Refined.unsafeApply(".*"))

    val vins = re.fold[FilterAST](True)(VinMatches)

    val pkgs = (pkgName, pkgVersion) match {
      case (Some(re1), Some(re2)) => HasPackage(toRegex(re1), toRegex(re2))
      case _ => True
    }

    val comps = part.fold[FilterAST](True)(r => HasComponent(toRegex(r)))

    val filter = And(vins, And(pkgs, comps))

    for {
      devices <- deviceRegistry.listNamespace()
      searchResult <- DbDepResolver.filterDevices(namespace, devices.map(d => d.uuid -> d.deviceId).toMap, filter)
    } yield searchResult
  }

  private def inSetQuery(ids: Set[PackageId]): Query[InstalledPackageTable, (Namespace, Uuid, PackageId), Seq] = {
    installedPackages.filter { pid =>
      (pid.packageName.mappedTo[String] ++ pid.packageVersion.mappedTo[String])
        .inSet(ids.map(id => id.name.get + id.version.get))
    }
  }

  def allInstalledPackagesById(namespace: Namespace, ids: Set[PackageId])
                              (implicit ec: ExecutionContext): DBIO[Map[Uuid, Seq[PackageId]]] = {
    inSetQuery(ids)
      .filter(_.namespace === namespace)
      .map(row => (row.device, LiftedPackageId(row.packageName, row.packageVersion)))
      .union(ForeignPackages.installedQuery(ids))
      .result
      .map {
        _.foldLeft(Map.empty[Uuid, Seq[PackageId]]) { case (acc, (device, pid)) =>
          val all = pid +: acc.getOrElse(device, Seq.empty[PackageId])
          acc + (device -> all)
        }
      }
  }
}
