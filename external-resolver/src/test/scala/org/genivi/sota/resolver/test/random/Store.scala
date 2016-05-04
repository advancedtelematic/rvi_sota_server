package org.genivi.sota.resolver.test.random

import cats.state.StateT
import eu.timepit.refined.api.{Refined, Validate}
import org.genivi.sota.data.PackageId
import org.genivi.sota.resolver.components.Component
import org.genivi.sota.resolver.devices.Device
import org.genivi.sota.resolver.filters.Filter
import org.genivi.sota.resolver.packages.Package
import org.scalacheck.Gen
import scala.collection.immutable.Iterable

import Misc._


// scalastyle:off number.of.methods
case class RawStore(
  devices  : Map[Device, (Set[Package], Set[Component])],
  packages  : Map[Package, Set[Filter]],
  filters   : Set[Filter],
  components: Set[Component],
  qryAggregates: QueryKindAggregates
) {

  /**
    * Used to communicate immutable data between the interpreter and the stats summarizer.
    */
  def withQrySems(sems: List[Semantics]): RawStore = {
    val justTheQueries = sems filter (_.isQuery)
    copy(qryAggregates = qryAggregates.merge(aggregateByQueryKind(justTheQueries)))
  }

  private def aggregateByQueryKind(qrySems: List[Semantics]): QueryKindAggregates = {
    val qsizes: List[(Query, Int)] = qrySems map { sem => (sem.original.get, sem.result.size.get)}
    val grouped: Map[Class[_], List[(Query, Int)]] = qsizes groupBy { case (q, s) => q.getClass }
    QueryKindAggregates(
      grouped mapValues { (lst: List[(Query, Int)]) =>
        QueryKindAggregate(lst.map(_._2).sum, lst.size)
      }
    )
  }

  // INSERTING

  def creating(device: Device): RawStore = {
    copy(devices = devices.updated(device, (Set.empty[Package], Set.empty[Component])))
  }

  def creating(pkg: Package): RawStore = {
    copy(packages = packages + (pkg -> Set.empty))
  }

  def creating(cmpn: Component): RawStore = {
    copy(components = components + cmpn)
  }

  def creating(filter: Filter): RawStore = {
    copy(filters = filters + filter)
  }

  // REPLACING

  def replacing(old: Filter, neu: Filter): RawStore = {
    var result = this
    val paksAffected = packagesHaving(old)
    for (p <- paksAffected) {
      val oldFilters = result.packages(p)
      val neuFilters = oldFilters - old + neu
      val neuPackages = result.packages.updated(p, neuFilters)
      result = result.copy(packages = neuPackages)
    }
    result = result.copy(filters = filters - old + neu)
    result
  }

  def replacing(old: Component, neu: Component): RawStore = {
    var result = this
    val devicesAffected = devicesHaving(old)
    for (v <- devicesAffected) {
      val (paks, oldComps) = result.devices(v)
      val neuComps = oldComps - old + neu
      val neuDevices = result.devices.updated(v, (paks, neuComps))
      result = result.copy(devices = neuDevices)
    }
    result = result.copy(components = components - old + neu)
    result
  }

  // REMOVING

  /**
    * Fails in case the given component is installed on any vin.
    * In that case,
    * [[org.genivi.sota.resolver.test.random.RawStore!.uninstalling(Device,Component):RawStore*]]
    * should have been invoked for each such vin before attempting to remove the component.
    */
  def removing(cmpn: Component): RawStore = {
    val installedOn = devicesHaving(cmpn)
    if (installedOn.nonEmpty) {
      val vins = installedOn.map(device => device.id.get).mkString
      throw new RuntimeException(s"Component $cmpn can't be removed, still installed in : $vins")
    }
    copy(components = components - cmpn)
  }

  /**
    * Fails in case the given filter is associated to some package.
    * In that case,
    * [[org.genivi.sota.resolver.test.random.RawStore!.deassociating(Package,Filter):RawStore*]]
    * should have been invoked for each such package before attempting to remove the filter.
    */
  def removing(flt: Filter): RawStore = {
    val associatedTo = packagesHaving(flt)
    if (associatedTo.nonEmpty) {
      val paks = associatedTo.map(pkg => pkg.id.toString).mkString
      throw new RuntimeException(s"Filter $flt can't be removed, still installed on : $paks")
    }
    copy(filters = filters - flt)
  }

  // COMPONENTS FOR DEVICES

  def installing(device: Device, cmpn: Component): RawStore = {
    val (paks, comps) = devices(device)
    copy(devices = devices.updated(device, (paks, comps + cmpn)))
  }

  def uninstalling(device: Device, cmpn: Component): RawStore = {
    val (paks, comps) = devices(device)
    copy(devices = devices.updated(device, (paks, comps - cmpn)))
  }

  // PACKAGES FOR DEVICES

  def installing(device: Device, pkg: Package): RawStore = {
    val (paks, comps) = devices(device)
    copy(devices = devices.updated(device, (paks + pkg, comps)))
  }

  def uninstalling(device: Device, pkg: Package): RawStore = {
    val (paks, comps) = devices(device)
    copy(devices = devices.updated(device, (paks - pkg, comps)))
  }

  // FILTERS FOR PACKAGES

  def associating(pkg: Package, filt: Filter): RawStore = {
    val existing = packages(pkg)
    copy(packages = packages.updated(pkg, existing + filt))
  }

  def deassociating(pkg: Package, filt: Filter): RawStore = {
    val existing = packages(pkg)
    copy(packages = packages.updated(pkg, existing - filt))
  }

  // QUERIES

  private def toSet[E](elems: Iterable[E]): Set[E] = { elems.toSet }

  /**
    * Devices with some package installed.
    */
  def devicesWithSomePackage: Map[Device, Set[Package]] = {
    for (
      (device, (packs, comps)) <- devices
      if packs.nonEmpty
    ) yield (device, packs)
  }

  /**
    * Devices with some component installed.
    */
  def devicesWithSomeComponent: Map[Device, Set[Component]] = {
    for (
      (device, (packs, comps)) <- devices
      if comps.nonEmpty
    ) yield (device, comps)
  }

  def packagesWithSomeFilter: Map[Package, Set[Filter]] = {
    for (
      (p, fs) <- packages
      if fs.nonEmpty
    ) yield (p, fs)
  }

  def devicesHaving(cmpn: Component): Set[Device] = toSet {
    for (
      (device, (paks, comps)) <- devices
      if comps.contains(cmpn)
    ) yield device
  }

  def devicesHaving(pkg: Package): Set[Device] = toSet {
    for (
      (device, (paks, comps)) <- devices
      if paks.contains(pkg)
    ) yield device
  }

  def packagesHaving(flt: Filter): Set[Package] = toSet {
    for (
      (pkg, fs) <- packages
      if fs contains flt
    ) yield pkg
  }

  def packagesInUse: Set[Package] = toSet {
    for (
      (device, (paks, comps)) <- devices;
      pkg <-  paks
    ) yield pkg
  }

  def componentsInUse: Set[Component] = toSet {
    for (
      (device, (paks, comps)) <- devices;
      cmpn  <-  comps
    ) yield cmpn
  }

  def filtersInUse: Set[Filter] = toSet {
    for (
      (pkg, fs) <- packages;
      flt  <-  fs
    ) yield flt
  }

  def packagesUnused: Set[Package] = { packages.keySet -- packagesInUse }

  def componentsUnused: Set[Component] = { components -- componentsInUse }

  def filtersUnused: Set[Filter] = { filters -- filtersInUse }

  // LOOKUPS

  private def toHead[A](elems: Iterable[A]): Option[A] = elems.headOption

  def lookupFilters(id: PackageId): Option[Set[Filter]] = toHead {
    for (
      (pkg, fs) <- packages
      if pkg.id == id
    ) yield fs
  }

  def lookupPkgsComps(vin: Device.DeviceId): Option[(Set[Package], Set[Component])] = toHead {
    for (
      (device, (paks, comps)) <- devices
      if device.id == vin
    ) yield (paks, comps)
  }

  def lookupPackages(vin: Device.DeviceId): Option[Set[Package]] =
    lookupPkgsComps(vin).map(_._1)

  def lookupComponents(vin: Device.DeviceId): Option[Set[Component]] =
    lookupPkgsComps(vin).map(_._2)

  // WELL-FORMEDNESS

  def isValid: Boolean = {
    devices.forall { entry =>
      val (_, (paks, comps)) = entry
      paks.forall(packages.contains) && comps.forall(components.contains)
    } && packages.forall { entry =>
      val (_, fs) = entry
      fs.forall(filters.contains)
    }
  }

}

object Store {

  val initRawStore: RawStore =
    RawStore(Map(), Map(), Set(), Set(), QueryKindAggregates(Map.empty))

  case class ValidStore()

  type Store = Refined[RawStore, ValidStore]

  implicit val validStore : Validate.Plain[RawStore, ValidStore] = Validate.fromPredicate(
    s => s.isValid,
    s => s"($s isn't a valid state)",
    ValidStore()
  )

  def pick[T](elems: collection.Iterable[T]): T = {
    // avoiding elems.toVector thus space-efficient
    val n = util.Random.nextInt(elems.size)
    val it = elems.iterator.drop(n)
    it.next
  }

  def pickDevice: StateT[Gen, RawStore, Device] =
    for {
      s    <- StateT.stateTMonadState[Gen, RawStore].get
      devices =  s.devices.keys
    } yield pick(devices)

  def pickPackage: StateT[Gen, RawStore, Package] =
    for {
      s    <- StateT.stateTMonadState[Gen, RawStore].get
      pkgs =  s.packages.keys
    } yield pick(pkgs)

  def pickFilter: StateT[Gen, RawStore, Filter] =
    for {
      s     <- StateT.stateTMonadState[Gen, RawStore].get
      filts =  s.filters
    } yield pick(filts)

  def pickUnusedFilter: StateT[Gen, RawStore, Filter] =
    for {
      s     <- StateT.stateTMonadState[Gen, RawStore].get
      uflts =  s.filtersUnused
    } yield pick(uflts)

  def pickComponent: StateT[Gen, RawStore, Component] =
    for {
      s    <- StateT.stateTMonadState[Gen, RawStore].get
      comps =  s.components
    } yield pick(comps)

  def pickUnusedComponent: StateT[Gen, RawStore, Component] =
    for {
      s    <- StateT.stateTMonadState[Gen, RawStore].get
      comps = s.componentsUnused
    } yield pick(comps)

  def pickDeviceWithComponent: StateT[Gen, RawStore, (Device, Component)] =
    for {
      s    <- StateT.stateTMonadState[Gen, RawStore].get
      vcs   = s.devicesWithSomeComponent
    } yield {
      val (device, comps) = pick(vcs)
      (device, pick(comps))
    }

  def pickDeviceWithPackage: StateT[Gen, RawStore, (Device, Package)] =
    for {
      s    <- StateT.stateTMonadState[Gen, RawStore].get
      vps   = s.devicesWithSomePackage
    } yield {
      val (device, paks) = pick(vps)
      (device, pick(paks))
    }

  def pickPackageWithFilter: StateT[Gen, RawStore, (Package, Filter)] =
    for {
      s    <- StateT.stateTMonadState[Gen, RawStore].get
      pfs   = s.packagesWithSomeFilter
    } yield {
      val (device, fs) = pick(pfs)
      (device, pick(fs))
    }

  def numberOfDevices: StateT[Gen, RawStore, Int] =
    StateT.stateTMonadState[Gen, RawStore].get map
      (_.devices.keys.size)

  def numberOfPackages: StateT[Gen, RawStore, Int] =
    StateT.stateTMonadState[Gen, RawStore].get map
      (_.packages.keys.size)

  def numberOfFilters: StateT[Gen, RawStore, Int] =
    StateT.stateTMonadState[Gen, RawStore].get map
      (_.filters.size)

  def numberOfUnusedFilters: StateT[Gen, RawStore, Int] =
    StateT.stateTMonadState[Gen, RawStore].get map
      (_.filtersUnused.size)

  def numberOfComponents: StateT[Gen, RawStore, Int] =
    StateT.stateTMonadState[Gen, RawStore].get map
      (_.components.size)

  def numberOfUnusedComponents: StateT[Gen, RawStore, Int] =
    StateT.stateTMonadState[Gen, RawStore].get map
      (_.componentsUnused.size)

  def numberOfDevicesWithSomePackage: StateT[Gen, RawStore, Int] =
    StateT.stateTMonadState[Gen, RawStore].get map
      (_.devicesWithSomePackage.size)

  def numberOfDevicesWithSomeComponent: StateT[Gen, RawStore, Int] =
    StateT.stateTMonadState[Gen, RawStore].get map
      (_.devicesWithSomeComponent.size)

  def numberOfPackagesWithSomeFilter: StateT[Gen, RawStore, Int] =
    StateT.stateTMonadState[Gen, RawStore].get map
      (_.packagesWithSomeFilter.size)
}
// scalastyle:on
