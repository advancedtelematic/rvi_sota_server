/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.devices

import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.PackageId
import org.genivi.sota.db.SlickExtensions._
import org.genivi.sota.refined.SlickRefined._
import org.genivi.sota.resolver.common.Errors
import org.genivi.sota.resolver.components.{Component, ComponentRepository}
import org.genivi.sota.resolver.data.Firmware
import org.genivi.sota.resolver.filters.FilterAST.{parseValidFilter, query}
import org.genivi.sota.resolver.filters.{And, FilterAST, HasComponent, HasPackage, True, VinMatches}
import org.genivi.sota.resolver.packages.{Package, PackageFilterRepository, PackageRepository}
import org.genivi.sota.resolver.resolve.ResolveFunctions
import org.joda.time._
import slick.driver.MySQLDriver.api._

import scala.concurrent.ExecutionContext


object DeviceRepository {

  // scalastyle:off
  class DeviceTable(tag: Tag) extends Table[Device](tag, "Device") {
    def namespace = column[Namespace]("namespace")
    def id = column[Device.DeviceId]("id")

    def * = (namespace, id) <> (Device.fromId, Device.toId)

    def pk = primaryKey("id", (namespace, id))  // insertOrUpdate doesn't work if
                                                // we use O.PrimaryKey in the id
                                                // column, see Slick issue #966.
  }
  // scalastyle:on

  val devices = TableQuery[DeviceTable]

  def add(device: Device): DBIO[Int] =
    devices.insertOrUpdate(device)

  def list: DBIO[Seq[Device]] =
    devices.result

  def exists(namespace: Namespace, id: Device.DeviceId)(implicit ec: ExecutionContext): DBIO[Device] =
    devices
      .filter(i => i.namespace === namespace && i.id === id)
      .result
      .headOption
      .flatMap(_.
        fold[DBIO[Device]](DBIO.failed(Errors.MissingDevice))(DBIO.successful))

  def delete(namespace: Namespace, id: Device.DeviceId): DBIO[Int] =
    devices.filter(i => i.namespace === namespace && i.id === id).delete

  def deleteDevice(namespace: Namespace, id: Device.DeviceId)
               (implicit ec: ExecutionContext): DBIO[Unit] =
    for {
      _ <- DeviceRepository.exists(namespace, id)
      _ <- deleteInstalledPackageByDevice(namespace, id)
      _ <- deleteInstalledComponentByDevice(namespace, id)
      _ <- delete(namespace, id)
    } yield ()

  /*
   * Installed firmware.
   */

  // scalastyle:off
  class InstalledFirmwareTable(tag: Tag) extends Table[(Firmware, Device.DeviceId)](tag, "Firmware") {

    def namespace     = column[Namespace]           ("namespace")
    def module        = column[Firmware.Module]     ("module")
    def firmware_id   = column[Firmware.FirmwareId] ("firmware_id")
    def last_modified = column[DateTime]            ("last_modified")
    def deviceId      = column[Device.DeviceId]     ("device_id")

    def pk = primaryKey("pk_installedFirmware", (namespace, module, firmware_id, deviceId))

    def * = (namespace, module, firmware_id, last_modified, deviceId).shaped <>
      (p => (Firmware(p._1, p._2, p._3, p._4), p._5),
        (fw: (Firmware, Device.DeviceId)) => Some((fw._1.namespace, fw._1.module, fw._1.firmwareId, fw._1.lastModified, fw._2)))
  }
  // scalastyle:on

  val installedFirmware = TableQuery[InstalledFirmwareTable]

  def firmwareExists(namespace: Namespace, module: Firmware.Module)
                    (implicit ec: ExecutionContext): DBIO[Firmware.Module] = {
    val res = for {
      ifw <- installedFirmware.filter(i => i.namespace === namespace && i.module === module).result.headOption
    } yield ifw
    res.flatMap(_.fold[DBIO[Firmware.Module]]
      (DBIO.failed(Errors.MissingFirmwareException))(x => DBIO.successful(x._1.module)))
  }

  def installFirmware
    (namespace: Namespace, module: Firmware.Module, firmware_id: Firmware.FirmwareId,
     last_modified: DateTime, deviceId: Device.DeviceId)
    (implicit ec: ExecutionContext): DBIO[Unit] = {
    for {
      _ <- exists(namespace, deviceId)
      _ <- firmwareExists(namespace, module)
      _ <- installedFirmware.insertOrUpdate((Firmware(namespace, module, firmware_id, last_modified), deviceId))
    } yield()
  }

  def firmwareOnDevice
    (namespace: Namespace, deviceId: Device.DeviceId)
    (implicit ec: ExecutionContext): DBIO[Seq[Firmware]] = {
    for {
      _  <- DeviceRepository.exists(namespace, deviceId)
      ps <- installedFirmware.filter(i => i.namespace === namespace && i.deviceId === deviceId).result
    } yield ps.map(_._1)
  }

  /*
   * Installed packages.
   */

  // scalastyle:off
  class InstalledPackageTable(tag: Tag) extends Table[(Namespace, Device.DeviceId, PackageId)](tag, "InstalledPackage") {

    def namespace      = column[Namespace]        ("namespace")
    def deviceId       = column[Device.DeviceId]  ("device_id")
    def packageName    = column[PackageId.Name]   ("packageName")
    def packageVersion = column[PackageId.Version]("packageVersion")

    def pk = primaryKey("pk_installedPackage", (namespace, deviceId, packageName, packageVersion))

    def * = (namespace, deviceId, packageName, packageVersion).shaped <>
      (p => (p._1, p._2, PackageId(p._3, p._4)),
      (vp: (Namespace, Device.DeviceId, PackageId)) => Some((vp._1, vp._2, vp._3.name, vp._3.version)))
  }
  // scalastyle:on

  val installedPackages = TableQuery[InstalledPackageTable]

  def installPackage
    (namespace: Namespace, deviceId: Device.DeviceId, pkgId: PackageId)
    (implicit ec: ExecutionContext): DBIO[Unit] =
    for {
      _ <- exists(namespace, deviceId)
      _ <- PackageRepository.exists(namespace, pkgId)
      _ <- installedPackages.insertOrUpdate((namespace, deviceId, pkgId))
    } yield ()

  def uninstallPackage
    (namespace: Namespace, deviceId: Device.DeviceId, pkgId: PackageId)
    (implicit ec: ExecutionContext): DBIO[Unit] =
    for {
      _ <- exists(namespace, deviceId)
      _ <- PackageRepository.exists(namespace, pkgId)
      _ <- installedPackages.filter {ip =>
             ip.namespace      === namespace &&
             ip.deviceId      === deviceId &&
             ip.packageName    === pkgId.name &&
             ip.packageVersion === pkgId.version
           }.delete
    } yield ()

  def updateInstalledPackages(namespace: Namespace, deviceId: Device.DeviceId, packages: Set[PackageId] )
                             (implicit ec: ExecutionContext): DBIO[Unit] = {

    def filterAvailablePackages( ids: Set[PackageId] ) : DBIO[Set[PackageId]] =
      PackageRepository.load(namespace, ids).map(_.map(_.id))

    def helper( device: Device, newPackages: Set[PackageId], deletedPackages: Set[PackageId] )
                               (implicit ec: ExecutionContext) : DBIO[Unit] = DBIO.seq(
      installedPackages.filter( ip =>
        ip.namespace === namespace &&
        ip.deviceId === device.id &&
        (ip.packageName.mappedTo[String] ++ ip.packageVersion.mappedTo[String])
          .inSet( deletedPackages.map( id => id.name.get + id.version.get ))
      ).delete,
      installedPackages ++= newPackages.map((namespace, device.id, _))
    ).transactionally

    for {
      device           <- DeviceRepository.exists(namespace, deviceId)
      installedPackages <- DeviceRepository.installedOn(namespace, deviceId)
      newPackages       =  packages -- installedPackages
      deletedPackages   =  installedPackages -- packages
      newAvailablePackages <- filterAvailablePackages(newPackages)
      _                 <- helper(device, newAvailablePackages, deletedPackages)
    } yield ()
  }

  def installedOn(namespace: Namespace, deviceId: Device.DeviceId)
                 (implicit ec: ExecutionContext) : DBIO[Set[PackageId]] =
    installedPackages.filter(i => i.namespace === namespace && i.deviceId === deviceId).result.map(_.map( _._3).toSet)

  def listInstalledPackages: DBIO[Seq[(Namespace, Device.DeviceId, PackageId)]] =
    installedPackages.result
    // TODO: namespaces?

  def deleteInstalledPackageByDevice(namespace: Namespace, deviceId: Device.DeviceId): DBIO[Int] =
    installedPackages.filter(i => i.namespace === namespace && i.deviceId === deviceId).delete

  def packagesOnDeviceMap
    (namespace: Namespace)
    (implicit ec: ExecutionContext)
      : DBIO[Map[Device.DeviceId, Seq[PackageId]]] =
    listInstalledPackages
      .map(_
        .filter(_._1 == namespace)
        .sortBy(_._2)
        .groupBy(_._2)
        .mapValues(_.map(_._3)))
    // TODO: namespaces?

  def packagesOnDevice
    (namespace: Namespace, deviceId: Device.DeviceId)
    (implicit ec: ExecutionContext): DBIO[Seq[PackageId]] =
    for {
      _  <- DeviceRepository.exists(namespace, deviceId)
      ps <- packagesOnDeviceMap(namespace)
              .map(_
                .get(deviceId)
                .toList
                .flatten)
    } yield ps

  /*
   * Installed components.
   */

  // scalastyle:off
  class InstalledComponentTable(tag: Tag)
      extends Table[(Namespace, Device.DeviceId, Component.PartNumber)](tag, "InstalledComponent") {

    def namespace  = column[Namespace]           ("namespace")
    def deviceId   = column[Device.DeviceId]     ("device_id")
    def partNumber = column[Component.PartNumber]("partNumber")

    def pk = primaryKey("pk_installedComponent", (namespace, deviceId, partNumber))

    def * = (namespace, deviceId, partNumber)
  }
  // scalastyle:on

  val installedComponents = TableQuery[InstalledComponentTable]

  def listInstalledComponents: DBIO[Seq[(Namespace, Device.DeviceId, Component.PartNumber)]] =
    installedComponents.result

  def deleteInstalledComponentByDevice(namespace: Namespace, deviceId: Device.DeviceId): DBIO[Int] =
    installedComponents.filter(i => i.namespace === namespace && i.deviceId === deviceId).delete

  def installComponent
    (namespace: Namespace, deviceId: Device.DeviceId, part: Component.PartNumber)
    (implicit ec: ExecutionContext): DBIO[Unit] =
    for {
      _ <- DeviceRepository.exists(namespace, deviceId)
      _ <- ComponentRepository.exists(namespace, part)
      _ <- installedComponents += ((namespace, deviceId, part))
    } yield ()

  def uninstallComponent
  (namespace: Namespace, deviceId: Device.DeviceId, part: Component.PartNumber)
  (implicit ec: ExecutionContext): DBIO[Unit] =
    for {
      _ <- exists(namespace, deviceId)
      _ <- ComponentRepository.exists(namespace, part)
      _ <- installedComponents.filter { ic =>
        ic.namespace  === namespace &&
        ic.deviceId  === deviceId &&
        ic.partNumber === part
      }.delete
    } yield ()

  def componentsOnDeviceMap
    (namespace: Namespace)
    (implicit ec: ExecutionContext): DBIO[Map[Device.DeviceId, Seq[Component.PartNumber]]] =
    DeviceRepository.listInstalledComponents
      .map(_
        .filter(_._1 == namespace)
        .sortBy(_._2)
        .groupBy(_._2)
        .mapValues(_.map(_._3)))

  def componentsOnDevice
    (namespace: Namespace, deviceId: Device.DeviceId)
    (implicit ec: ExecutionContext): DBIO[Seq[Component.PartNumber]] =
    for {
      _  <- exists(namespace, deviceId)
      cs <- componentsOnDeviceMap(namespace)
              .map(_
                .get(deviceId)
                .toList
                .flatten)
    } yield cs

  def devicesWithPackagesAndComponents
    (namespace: Namespace)
    (implicit ec: ExecutionContext)
      : DBIO[Seq[(Device, (Seq[PackageId], Seq[Component.PartNumber]))]] =
    for {
      vs   <- DeviceRepository.list
      ps   : Seq[Seq[PackageId]]
           <- DBIO.sequence(vs.map(d => DeviceRepository.packagesOnDevice(namespace, d.id)))
      cs   : Seq[Seq[Component.PartNumber]]
           <- DBIO.sequence(vs.map(d => DeviceRepository.componentsOnDevice(namespace, d.id)))
      vpcs : Seq[(Device, (Seq[PackageId], Seq[Component.PartNumber]))]
           =  vs.zip(ps.zip(cs))
    } yield vpcs
    // TODO: namespaces?

  /*
   * Searching
   */

  def search(namespace : Namespace,
             re        : Option[Refined[String, Regex]],
             pkgName   : Option[PackageId.Name],
             pkgVersion: Option[PackageId.Version],
             part      : Option[Component.PartNumber])
            (implicit ec: ExecutionContext): DBIO[Seq[Device]] = {

    def toRegex[T](r: Refined[String, T]): Refined[String, Regex] =
      Refined.unsafeApply(r.get)

    val devices  = re.fold[FilterAST](True)(VinMatches(_))
    val pkgs  = (pkgName, pkgVersion) match
      { case (Some(re1), Some(re2)) => HasPackage(toRegex(re1), toRegex(re2))
        case _                      => True
      }
    val comps = part.fold[FilterAST](True)(r => HasComponent(toRegex(r)))

    for {
      vpcs <- devicesWithPackagesAndComponents(namespace)
    } yield vpcs.filter(query(And(devices, And(pkgs, comps)))).map(_._1)

  }

  /*
   * Resolving package dependencies.
   */

  def resolve(namespace: Namespace, pkgId: PackageId)
             (implicit ec: ExecutionContext): DBIO[Map[Device.DeviceId, Seq[PackageId]]] =
    for {
      _    <- PackageRepository.exists(namespace, pkgId)
      fs   <- PackageFilterRepository.listFiltersForPackage(namespace, pkgId)
      vpcs <- devicesWithPackagesAndComponents(namespace)
    } yield ResolveFunctions.makeFakeDependencyMap(pkgId,
              vpcs.filter(query(fs.map(_.expression).map(parseValidFilter).foldLeft[FilterAST](True)(And)))
                  .map(_._1))
}
