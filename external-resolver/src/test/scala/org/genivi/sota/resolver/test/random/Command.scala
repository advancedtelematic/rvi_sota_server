package org.genivi.sota.resolver.test.random

import akka.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes}
import cats.state.{State, StateT}
import org.genivi.sota.data.Namespaces
import org.genivi.sota.resolver.components.Component
import org.genivi.sota.resolver.devices.Device
import org.genivi.sota.resolver.filters.Filter
import org.genivi.sota.resolver.packages.{Package, PackageFilter}
import org.genivi.sota.resolver.test._
import org.genivi.sota.rest.ErrorCodes
import org.scalacheck.Gen

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import Misc._


sealed trait Command

final case class AddDevice(device: Device) extends Command
final case class AddPackage(pkg: Package) extends Command

final case class InstallPackage  (device: Device, pkg: Package) extends Command
final case class UninstallPackage(device: Device, pkg: Package) extends Command

final case class AddFilter (filt: Filter)               extends Command
final case class EditFilter(old : Filter, neu: Filter)  extends Command {
  // restriction imposed by the endpoint allowing only non-PK fields to be updated.
  if (!(old.samePK(neu))) throw new IllegalArgumentException
}
final case class RemoveFilter          (filt: Filter)               extends Command
final case class AddFilterToPackage    (pkg: Package, filt: Filter) extends Command
final case class RemoveFilterForPackage(pkg: Package, filt: Filter) extends Command

final case class AddComponent   (cmpn: Component)                  extends Command
final case class EditComponent  (old : Component, neu: Component)  extends Command {
  // restriction imposed by the endpoint allowing only non-PK fields to be updated.
  if (!(old.samePK(neu))) throw new IllegalArgumentException
}
final case class RemoveComponent(cmpn: Component)                  extends Command
final case class InstallComponent  (device: Device, cmpn: Component) extends Command
final case class UninstallComponent(device: Device, cmpn: Component) extends Command


object Command extends
    DeviceRequestsHttp with
    PackageRequestsHttp with
    FilterRequestsHttp  with
    ComponentRequestsHttp with
    PackageFilterRequestsHttp with
    Namespaces {

  type SemCommand = (HttpRequest, StatusCode, Result)

  def semCommands(cmds: List[Command])
                 (implicit ec: ExecutionContext): State[RawStore, List[Semantics]] = {

    @tailrec def go(cmds0: List[Command], s0: RawStore, acc: List[Semantics]): (RawStore, List[Semantics]) =
      cmds0 match {
        case Nil            => (s0, acc.reverse)
        case (cmd :: cmds1) =>
          val (s1, r) = semCommand(cmd).run(s0).run
          go(cmds1, s1, r :: acc)
      }

    State.get.flatMap { s0 =>
      val (s1, sems) = go(cmds, s0, List())
      State.set(s1).flatMap(_ => State.pure(sems))
    }

  }

  // scalastyle:off cyclomatic.complexity
  // scalastyle:off method.length
  def semCommand(cmd: Command)
                (implicit ec: ExecutionContext): State[RawStore, Semantics] = cmd match {

    case AddDevice(device) =>
      for {
        s <- State.get
        _ <- State.set(s.creating(device))
      } yield Semantics(addDevice(device.id), StatusCodes.NoContent, Success)

    case AddPackage(pkg) =>
      for {
        s <- State.get
        _ <- State.set(s.creating(pkg))
      } yield Semantics(addPackage(pkg), StatusCodes.OK, SuccessPackage(pkg))

    case InstallPackage(device, pkg) =>
      for {
        s <- State.get
        _ <- State.set(s.installing(device, pkg))
      } yield Semantics(installPackage(device, pkg), StatusCodes.OK, Success)

    case UninstallPackage(device, pkg) =>
      for {
        s <- State.get
        _ <- State.set(s.uninstalling(device, pkg))
      } yield Semantics(
        uninstallPackage(device, pkg),
        StatusCodes.OK, Success) // whether already uninstalled or not, OK is the reply

    case AddFilter(filt) =>
      for {
        s <- State.get
        _ <- State.set(s.creating(filt))
      } yield Semantics(addFilter2(filt), StatusCodes.OK, Success)

    case EditFilter(old, neu) =>
      for {
        s <- State.get
        _ <- State.set(s.replacing(old, neu))
      } yield Semantics(updateFilter(neu), StatusCodes.OK, Success)

    case RemoveFilter(filt) =>
      for {
        s       <- State.get
        _       <- State.set(s.removing(filt))
        success =  s.filtersUnused.contains(filt)
      } yield {
        val req = deleteFilter(filt)
        if (success) { Semantics(req, StatusCodes.OK, Success) }
        else         { Semantics(req, StatusCodes.Conflict, Failure(ErrorCodes.DuplicateEntry)) }
      }

    case AddFilterToPackage(pkg, filt) =>
      for {
        s       <- State.get
        _       <- State.set(s.associating(pkg, filt))
        success =  !s.packages(pkg).contains(filt)
      } yield {
        val req = addPackageFilter2(PackageFilter(pkg.namespace, pkg.id.name, pkg.id.version, filt.name))
        if (success) { Semantics(req, StatusCodes.OK, Success) }
        else         { Semantics(req, StatusCodes.Conflict, Failure(ErrorCodes.DuplicateEntry)) }
      }

    case RemoveFilterForPackage(pkg, filt) =>
      for {
        s       <- State.get
        _       <- State.set(s.deassociating(pkg, filt))
      } yield Semantics(
        deletePackageFilter(pkg, filt),
        StatusCodes.OK, Success)

    case AddComponent(cmpn) =>
      for {
        s <- State.get
        _ <- State.set(s.creating(cmpn))
        isDuplicatePK = s.components.exists(_.partNumber == cmpn.partNumber)
      } yield {
        val req = addComponent(cmpn.partNumber, cmpn.description)
        if (isDuplicatePK) { Semantics(req, StatusCodes.Conflict, Failure(ErrorCodes.DuplicateEntry)) }
        else               { Semantics(req, StatusCodes.OK, Success) }
      }

    case EditComponent(old, neu) =>
      for {
        s <- State.get
        _ <- State.set(s.replacing(old, neu))
      } yield Semantics(updateComponent(neu), StatusCodes.OK, Success)

    case RemoveComponent(cmpn) =>
      for {
        s <- State.get
        _ <- State.set(s.removing(cmpn))
        success = s.componentsUnused.contains(cmpn)
      } yield {
        val req = deleteComponent(cmpn.partNumber)
        if (success) { Semantics(req, StatusCodes.OK, Success) }
        else         { Semantics(req, StatusCodes.Conflict, Failure(ErrorCodes.DuplicateEntry)) }
      }

    case InstallComponent(device, cmpn) =>
      for {
        s <- State.get
        _ <- State.set(s.installing(device, cmpn))
        isDuplicatePK = s.devices(device)._2.contains(cmpn)
      } yield {
        val req = installComponent(device, cmpn)
        if (isDuplicatePK) { Semantics(req, StatusCodes.Conflict, Failure(ErrorCodes.DuplicateEntry)) }
        else               { Semantics(req, StatusCodes.OK, Success) }
      }

    case UninstallComponent(device, cmpn) =>
      for {
        s <- State.get
        _ <- State.set(s.uninstalling(device, cmpn))
      } yield Semantics(
        uninstallComponent(device, cmpn),
        StatusCodes.OK, Success) // whether already uninstalled or not, OK is the reply

  }
  // scalastyle:on

  private def genCommandAddDevice: Gen[AddDevice] =
    DeviceGenerators.genDevice.map(AddDevice(_))

  private def genCommandAddPackage: Gen[AddPackage] =
    PackageGenerators.genPackage.map(AddPackage(_))

  private def genCommandAddFilter(s: RawStore): Gen[AddFilter] =
    FilterGenerators.genFilter(s.packages.keys.toList, s.components.toList)
      .map(AddFilter(_))

  private def genCommandAddComponent(s: RawStore): Gen[AddComponent] =
    ComponentGenerators.genComponent.map(AddComponent(_))

  private def genCommandInstallPackage(s: RawStore): Gen[InstallPackage] =
    for {
      device <- Store.pickDevice.runA(s)
      pkg <- Store.pickPackage.runA(s)
    } yield InstallPackage(device, pkg)

  private def genCommandUninstallPackage(s: RawStore): Gen[UninstallPackage] =
    for {
      (device, pkg) <- Store.pickDeviceWithPackage.runA(s)
    } yield UninstallPackage(device, pkg)

  private def genCommandInstallComponent(s: RawStore): Gen[InstallComponent] =
    for {
      device <- Store.pickDevice.runA(s)
      cmp <- Store.pickComponent.runA(s)
    } yield InstallComponent(device, cmp)

  private def genCommandUninstallComponent(s: RawStore): Gen[UninstallComponent] =
    for {
      (device, cmp) <- Store.pickDeviceWithComponent.runA(s)
    } yield UninstallComponent(device, cmp)

  private def genCommandAddFilterToPackage(s: RawStore): Gen[AddFilterToPackage] =
    for {
      pkg  <- Store.pickPackage.runA(s)
      filt <- Store.pickFilter.runA(s)
    } yield AddFilterToPackage(pkg, filt)

  private def genCommandRemoveFilterForPackage(s: RawStore): Gen[RemoveFilterForPackage] =
    for {
      (pkg, flt)  <- Store.pickPackageWithFilter.runA(s)
    } yield RemoveFilterForPackage(pkg, flt)

  private def genCommandEditFilter(s: RawStore): Gen[EditFilter] =
    for {
      fltOld <- Store.pickFilter.runA(s)
      fltNu0 <- FilterGenerators.genFilter(s.packages.keys.toList, s.components.toList)
      fltNu1  = Filter(defaultNs, fltOld.name, fltNu0.expression)
    } yield EditFilter(fltOld, fltNu1)

  /**
    * Pick an unsed filter for removal.
    * Note: [[semCommand]] can handle the case where the filter is in use, only we don't exercise such case.
    */
  private def genCommandRemoveFilter(s: RawStore): Gen[RemoveFilter] =
    for {
      flt  <- Store.pickUnusedFilter.runA(s)
    } yield RemoveFilter(flt)

  /**
    * Pick an unsed component for removal.
    * Note: [[semCommand]] can handle the case where the component is in use, only we don't exercise such case.
    */
  private def genCommandRemoveComponent(s: RawStore): Gen[RemoveComponent] =
    for {
      cmp  <- Store.pickUnusedComponent.runA(s)
    } yield RemoveComponent(cmp)

  private def genCommandEditComponent(s: RawStore): Gen[EditComponent] =
    for {
      cmpOld <- Store.pickComponent.runA(s)
      cmpNu0 <- ComponentGenerators.genComponent
      cmpNu1  = Component(defaultNs, cmpOld.partNumber, cmpNu0.description)
    } yield EditComponent(cmpOld, cmpNu1)

  // scalastyle:off cyclomatic.complexity
  // scalastyle:off magic.number
  def genCommand(implicit ec: ExecutionContext): StateT[Gen, RawStore, Command] =
    for {
      s     <- StateT.stateTMonadState[Gen, RawStore].get
      devices  <- Store.numberOfDevices
      pkgs  <- Store.numberOfPackages
      filts <- Store.numberOfFilters
      uflts <- Store.numberOfUnusedFilters
      comps <- Store.numberOfComponents
      ucmps <- Store.numberOfUnusedComponents
      vcomp <- Store.numberOfDevicesWithSomeComponent
      vpaks <- Store.numberOfDevicesWithSomePackage
      pfilt <- Store.numberOfPackagesWithSomeFilter
      cmd   <- lift(Gen.frequency(

        // If there are few devices, packages or filters in the world,
        // then generate some with high probability.

        (if (devices <= 10) 100 else 1, genCommandAddDevice),

        (if (pkgs <= 5)  100 else 1, genCommandAddPackage),

        (if (filts <= 3) 20 else 1, genCommandAddFilter(s)),

        (if (comps <= 3) 20 else 1, genCommandAddComponent(s)),

        // If there are devices and packages, then install some
        // packages on the devices with high probability.
        (if (devices > 0 && pkgs > 0) 100 else 0, genCommandInstallPackage(s)),

        // If there are devices and components
        (if (devices > 0 && comps > 0) 100 else 0, genCommandInstallComponent(s)),

        (if (vpaks > 0) 10 else 0, genCommandUninstallPackage(s)),

        (if (vcomp > 0) 10 else 0, genCommandUninstallComponent(s)),

        (if (filts > 0) 50 else 0, genCommandEditFilter(s)),

        (if (comps > 0) 50 else 0, genCommandEditComponent(s)),

        // If there are packages and filters, install some filter to some package.
        (if (pkgs > 0 && filts > 0) 50 else 0, genCommandAddFilterToPackage(s)),

        (if (pfilt > 0) 50 else 0, genCommandRemoveFilterForPackage(s)),

        (if (uflts > 0) 50 else 0, genCommandRemoveFilter(s)),

        (if (ucmps > 0) 50 else 0, genCommandRemoveComponent(s))

      ))
      _   <- StateT.stateTMonadState(monGen).set(semCommand(cmd).runS(s).run)
    } yield cmd
  // scalastyle:on

  def genCommands(n: Int)
                 (implicit ec: ExecutionContext): StateT[Gen, RawStore, List[Command]] = {
    if (n < 1) throw new IllegalArgumentException
    for {
      cmd  <- genCommand
      cmds <- if (n == 1) genCommand.map(List(_)) else genCommands(n - 1)
    } yield cmd :: cmds
  }

}
