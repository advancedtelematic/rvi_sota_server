package org.genivi.sota.resolver.test.random

import Misc.{function0Instance, lift, monGen}
import akka.http.scaladsl.model.StatusCodes
import cats.state.{State, StateT}
import org.genivi.sota.data.PackageId
import org.genivi.sota.resolver.components.Component
import org.genivi.sota.resolver.devices.Device
import org.genivi.sota.resolver.filters.Filter
import org.genivi.sota.resolver.filters.{And, FilterAST, True}
import org.genivi.sota.resolver.packages.Package
import org.genivi.sota.resolver.resolve.ResolveFunctions
import org.genivi.sota.resolver.test._
import org.scalacheck.Gen
import scala.annotation.tailrec
import scala.collection.immutable.Iterable
import scala.concurrent.ExecutionContext

import FilterAST._


sealed trait Query

final case object ListDevices                          extends Query
final case class  ListPackagesOnDevice(device: Device) extends Query
final case class  ListDevicesFor(cmp: Component)       extends Query
final case class  ListPackagesFor(flt: Filter)         extends Query

final case object ListFilters                          extends Query
final case class  ListFiltersFor(pak: Package)         extends Query

final case class  Resolve(id: PackageId)               extends Query

final case object ListComponents                       extends Query
final case class  ListComponentsFor(device: Device)    extends Query

object Query extends
    DeviceRequestsHttp with
    PackageRequestsHttp with
    FilterRequestsHttp with
    ComponentRequestsHttp with
    PackageFilterRequestsHttp with
    ResolveRequestsHttp {

  def semQueries(qrs: List[Query])
                (implicit ec: ExecutionContext): State[RawStore, List[Semantics]] = {

    @tailrec def go(qrs0: List[Query], s0: RawStore, acc: List[Semantics]): (RawStore, List[Semantics]) =
      qrs0 match {
        case Nil          => (s0, acc.reverse)
        case (qr :: qrs1) =>
          val (s1, r) = semQuery(qr).run(s0).run
          go(qrs1, s1, r :: acc)
      }

    State.get.flatMap { s0 =>
      val (s1, sems) = go(qrs, s0, List())
      State.set(s1).flatMap(_ => State.pure(sems))
    }

  }

  def semQuery(q: Query): State[RawStore, Semantics] = q match {

    case ListDevices =>
      State.get map (s => Semantics(Some(q),
        listDevices, StatusCodes.OK,
        SuccessDevices(s.devices.keySet)))

    case ListDevicesFor(cmp) =>
      State.get map (s => Semantics(Some(q),
        listDevicesHaving(cmp), StatusCodes.OK,
        SuccessDevices(s.devicesHaving(cmp))))

    case ListComponents =>
      State.get map (s => Semantics(Some(q),
        listComponents, StatusCodes.OK,
        SuccessComponents(s.components)))

    case ListComponentsFor(device) =>
      State.get map (s => Semantics(Some(q),
        listComponentsOnDevice(device), StatusCodes.OK,
        SuccessPartNumbers(s.devices(device)._2.map(_.partNumber))))

    case ListPackagesOnDevice(device) =>
      State.get map (s => Semantics(Some(q),
        listPackagesOnDevice(device), StatusCodes.OK,
        SuccessPackageIds(s.devices(device)._1.map(_.id))))

    case ListPackagesFor(flt) =>
      State.get map (s => Semantics(Some(q),
        listPackagesForFilter(flt), StatusCodes.OK,
        SuccessPackages(s.packagesHaving(flt))))

    case ListFilters =>
      State.get map (s => Semantics(Some(q),
        listFilters, StatusCodes.OK,
        SuccessFilters(s.filters)))

    case ListFiltersFor(pak) =>
      State.get map (s => Semantics(Some(q),
        listFiltersForPackage(pak), StatusCodes.OK,
        SuccessFilters(s.packages(pak))))

    case Resolve(pkgId) =>
      State.get map (s => Semantics(Some(q),
        resolve2(pkgId), StatusCodes.OK,
        SuccessDeviceMap(deviceMap(s, pkgId))))

  }

  private def deviceMap(s: RawStore, pkgId: PackageId): Map[Device.DeviceId, List[PackageId]] = {

    // An AST for each filter associated to the given package.
    val filters: Set[FilterAST] =
      for (
        flt <- s.lookupFilters(pkgId).get
      ) yield parseValidFilter(flt.expression)

    // An AST AND-ing the filters associated to the given package.
    val expr: FilterAST =
      filters.toList.foldLeft[FilterAST](True)(And)

    // Apply the resulting filter to select devices.
    val devices: Iterable[Device] = for (
      (device, (paks, comps)) <- s.devices;
      pakIds = paks.map(_.id).toSeq;
      compIds = comps.map(_.partNumber).toSeq;
      entry2 = (device, (pakIds, compIds));
      if query(expr)(entry2)
    ) yield device

    ResolveFunctions.makeFakeDependencyMap(pkgId, devices.toSeq)
  }

  // scalastyle:off magic.number
  def genQuery: StateT[Gen, RawStore, Query] =
    for {
      s    <- StateT.stateTMonadState[Gen, RawStore].get
      devices <- Store.numberOfDevices
      pkgs <- Store.numberOfPackages
      cmps <- Store.numberOfComponents
      flts <- Store.numberOfFilters
      vcomp <- Store.numberOfDevicesWithSomeComponent
      vpaks <- Store.numberOfDevicesWithSomePackage
      pfilt <- Store.numberOfPackagesWithSomeFilter
      qry  <- lift(Gen.frequency(

        (10, Gen.const(ListDevices)),
        (10, Gen.const(ListComponents)),
        ( 5, Gen.const(ListFilters)),

        (if (devices > 0) 10 else 0, Gen.oneOf(
          Store.pickDevice.runA(s).map(ListPackagesOnDevice(_)),
          Store.pickDevice.runA(s).map(ListComponentsFor(_))
        )),

        (if (vcomp > 0) 10 else 0,
          Store.pickDeviceWithComponent.runA(s) map { case (device, cmp) => ListDevicesFor(cmp) }),

        (if (pfilt > 0) 10 else 0, Gen.oneOf(
          Store.pickPackageWithFilter.runA(s) map { case (pkg, flt) => ListPackagesFor(flt) },
          Store.pickPackageWithFilter.runA(s) map { case (pkg, flt) => ListFiltersFor(pkg)  }
        )),

        (if (pkgs > 0) 50 else 0,
          Store.pickPackage.runA(s).map(pkg => Resolve(pkg.id)))
      ))
    } yield qry
  // scalastyle:on

  def genQueries(n: Int)
                (implicit ec: ExecutionContext): StateT[Gen, RawStore, List[Query]] = {
    if (n < 1) throw new IllegalArgumentException
    for {
      q  <- genQuery
      qs <- if (n == 1) genQuery.map(List(_)) else genQueries(n - 1)
    } yield q :: qs
  }

}
