/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.test

import akka.http.scaladsl.client.RequestBuilding.{Delete, Get, Post, Put}
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import eu.timepit.refined.api.Refined
import io.circe.generic.auto._
import org.genivi.sota.data.{Namespaces, PackageId}
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.resolver.components.Component
import org.genivi.sota.resolver.devices.Device
import org.genivi.sota.resolver.filters.Filter
import org.genivi.sota.resolver.packages.{Package, PackageFilter}
import org.genivi.sota.resolver.resolve.ResolveFunctions
import org.scalatest.Matchers
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


/**
 * Generic test resource object
 * Used in property-based testing
 */
object Resource {
  def uri(pathSuffixes: String*): Uri = {
    val BasePath = Path("/api") / "v1"
    Uri.Empty.withPath(pathSuffixes.foldLeft(BasePath)(_/_))
  }
}

/**
 * Testing Trait for building Device requests
 */
trait DeviceRequestsHttp {

  def addDevice(deviceId: Device.DeviceId): HttpRequest =
    Put(Resource.uri("devices", deviceId.get))

  def installPackage(device: Device, pkg: Package): HttpRequest =
    installPackage(device.id, pkg.id.name.get, pkg.id.version.get)

  def installPackage(deviceId: Device.DeviceId, pname: String, pversion: String): HttpRequest =
    Put(Resource.uri("devices", deviceId.get, "package", pname, pversion))

  def uninstallPackage(device: Device, pkg: Package): HttpRequest =
    uninstallPackage(device.id, pkg.id.name.get, pkg.id.version.get)

  def uninstallPackage(deviceId: Device.DeviceId, pname: String, pversion: String): HttpRequest =
    Delete(Resource.uri("devices", deviceId.get, "package", pname, pversion))

  def listDevices: HttpRequest =
    Get(Resource.uri("devices"))

  def listDevicesHaving(cmp: Component): HttpRequest =
    listDevicesHaving(cmp.partNumber.get)

  def listDevicesHaving(partNumber: String): HttpRequest =
    Get(Resource.uri("devices").withQuery(Query("component" -> partNumber)))

  def listPackagesOnDevice(device: Device): HttpRequest =
    Get(Resource.uri("devices", device.id.get, "package"))

  def listComponentsOnDevice(device: Device): HttpRequest =
    listComponentsOnDevice(device.id.get)

  def listComponentsOnDevice(deviceId: String): HttpRequest =
    Get(Resource.uri("devices", deviceId, "component"))

  private def path(deviceId: Device.DeviceId, part: Component.PartNumber): Uri =
    Resource.uri("devices", deviceId.get, "component", part.get)

  def installComponent(device: Device, cmpn: Component): HttpRequest =
    installComponent(device.id, cmpn.partNumber)

  def installComponent(deviceId: Device.DeviceId, part: Component.PartNumber): HttpRequest =
    Put(path(deviceId, part))

  def uninstallComponent(device: Device, cmpn: Component): HttpRequest =
    uninstallComponent(device.id, cmpn.partNumber)

  def uninstallComponent(deviceId: Device.DeviceId, part: Component.PartNumber): HttpRequest =
    Delete(path(deviceId, part))

}

trait DeviceRequests extends
    DeviceRequestsHttp with
    PackageRequestsHttp with
    Matchers { self: ScalatestRouteTest =>

  def addDeviceOK(deviceId: Device.DeviceId)(implicit route: Route): Unit = {

    implicit val routeTimeout: RouteTestTimeout = RouteTestTimeout(5.second)

    addDevice(deviceId) ~> route ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  def installPackageOK(deviceId: Device.DeviceId, pname: String, pversion: String)(implicit route: Route): Unit =
    installPackage(deviceId, pname, pversion) ~> route ~> check {
      status shouldBe StatusCodes.OK
    }

  def installComponentOK(deviceId: Device.DeviceId, part: Component.PartNumber)
                        (implicit route: Route): Unit =
    installComponent(deviceId, part) ~> route ~> check {
      status shouldBe StatusCodes.OK
    }

  def uninstallComponentOK(deviceId: Device.DeviceId, part: Component.PartNumber)
                          (implicit route: Route): Unit =
    uninstallComponent(deviceId, part) ~> route ~> check {
      status shouldBe StatusCodes.OK
    }

}

/**
  * Testing Trait for building Package requests
  */
trait PackageRequestsHttp {

  def addPackage(pkg: Package)
                (implicit ec: ExecutionContext): HttpRequest =
    addPackage(pkg.id.name.get, pkg.id.version.get, pkg.description, pkg.vendor)

  def addPackage(name: String, version: String, desc: Option[String], vendor: Option[String])
                (implicit ec: ExecutionContext): HttpRequest =
    Put(Resource.uri("packages", name, version), Package.Metadata(desc, vendor))

}

trait PackageRequests extends
  PackageRequestsHttp with
  Matchers { self: ScalatestRouteTest =>

  def addPackageOK(name: String, version: String, desc: Option[String], vendor: Option[String])
                  (implicit route: Route): Unit =
    addPackage(name, version, desc, vendor) ~> route ~> check {
      status shouldBe StatusCodes.OK
    }
}

/**
 * Testing Trait for building Component requests
 */
trait ComponentRequestsHttp {

  def listComponents: HttpRequest =
    Get(Resource.uri("components"))

  def addComponent(part: Component.PartNumber, desc: String)
                  (implicit ec: ExecutionContext): HttpRequest =
    Put(Resource.uri("components", part.get), Component.DescriptionWrapper(desc))

  def deleteComponent(part: Component.PartNumber): HttpRequest =
    Delete(Resource.uri("components", part.get))

  def updateComponent(cmp: Component)
                     (implicit ec: ExecutionContext): HttpRequest =
    updateComponent(cmp.partNumber.get, cmp.description)

  def updateComponent(partNumber: String, description: String)
                     (implicit ec: ExecutionContext): HttpRequest =
    Put(Resource.uri("components", partNumber), Component.DescriptionWrapper(description))

}

trait ComponentRequests extends
    ComponentRequestsHttp with
    Matchers { self: ScalatestRouteTest =>

  def addComponentOK(part: Component.PartNumber, desc: String)
                    (implicit route: Route): Unit =
    addComponent(part, desc) ~> route ~> check {
      status shouldBe StatusCodes.OK
    }

}

/**
 * Testing Trait for building Filter requests
 */
trait FilterRequestsHttp extends Namespaces {

  def addFilter(name: String, expr: String)
               (implicit ec: ExecutionContext): HttpRequest =
    addFilter2(Filter(defaultNs, Refined.unsafeApply(name), Refined.unsafeApply(expr)))

  def addFilter2(filt: Filter)
                (implicit ec: ExecutionContext): HttpRequest =
    Post(Resource.uri("filters"), filt)

  def updateFilter(filt: Filter)
                  (implicit ec: ExecutionContext): HttpRequest =
    updateFilter(filt.name.get, filt.expression.get)

  def updateFilter(name: String, expr: String)
                  (implicit ec: ExecutionContext): HttpRequest =
    Put(Resource.uri("filters", name), Filter.ExpressionWrapper(Refined.unsafeApply(expr)))

  def listFilters: HttpRequest =
    Get(Resource.uri("filters"))

  def deleteFilter(filt: Filter): HttpRequest =
    deleteFilter(filt.name.get)

  def deleteFilter(name: String): HttpRequest =
    Delete(Resource.uri("filters", name))

  def listFiltersRegex(re: String): HttpRequest =
    Get(Resource.uri("filters") + "?regex=" + re)

  def validateFilter(filter: Filter)
                    (implicit ec: ExecutionContext): HttpRequest =
    Post(Resource.uri("validate", "filter"), filter)

}

trait FilterRequests extends FilterRequestsHttp with Matchers { self: ScalatestRouteTest =>

  def addFilterOK(name: String, expr: String)(implicit route: Route): Unit = {

    implicit val routeTimeout: RouteTestTimeout = RouteTestTimeout(5.second)

    addFilter(name, expr) ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Filter] shouldBe Filter(defaultNs, Refined.unsafeApply(name), Refined.unsafeApply(expr))
    }
  }

  def updateFilterOK(name: String, expr: String)(implicit route: Route): Unit =
    updateFilter(name, expr) ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Filter] shouldBe Filter(defaultNs, Refined.unsafeApply(name), Refined.unsafeApply(expr))
    }

  def deleteFilterOK(name: String)(implicit route: Route): Unit =
    deleteFilter(name) ~> route ~> check {
      status shouldBe StatusCodes.OK
    }

}

/**
 * Testing Trait for building PackageFilter requests
 */
trait PackageFilterRequestsHttp {

  def addPackageFilter2(pf: PackageFilter): HttpRequest = {
    Put(Resource.uri("packages", pf.packageName.get, pf.packageVersion.get, "filter", pf.filterName.get))
  }

  def addPackageFilter(pname: String, pversion: String, fname: String): HttpRequest =
    Put(Resource.uri("packages", pname, pversion, "filter", fname))

  def listPackageFilters: HttpRequest =
    Get(Resource.uri("packages", "filter"))

  def listPackagesForFilter(flt: Filter): HttpRequest =
    listPackagesForFilter(flt.name.get)

  def listPackagesForFilter(fname: String): HttpRequest =
    Get(Resource.uri("filters", fname, "package"))

  def listFiltersForPackage(pak: Package): HttpRequest =
    listFiltersForPackage(pak.id.name.get, pak.id.version.get)

  def listFiltersForPackage(pname: String, pversion: String): HttpRequest =
    Get(Resource.uri("packages", pname, pversion, "filter"))

  def deletePackageFilter(pkg: Package, filt: Filter): HttpRequest =
    deletePackageFilter(pkg.id.name.get, pkg.id.version.get, filt.name.get)

  def deletePackageFilter(pname: String, pversion: String, fname: String): HttpRequest =
    Delete(Resource.uri("packages", pname, pversion, "filter", fname))

}

trait PackageFilterRequests extends
  PackageFilterRequestsHttp with
  Matchers with
  Namespaces { self: ScalatestRouteTest =>

  def addPackageFilterOK(pname: String, pversion: String, fname: String)(implicit route: Route): Unit =
    addPackageFilter(pname, pversion, fname) ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[PackageFilter] shouldBe
        PackageFilter(defaultNs, Refined.unsafeApply(pname), Refined.unsafeApply(pversion), Refined.unsafeApply(fname))
    }

  def deletePackageFilterOK(pname: String, pversion: String, fname: String)(implicit route: Route): Unit =
    deletePackageFilter(pname, pversion, fname) ~> route ~> check {
      status shouldBe StatusCodes.OK
    }
}

/**
 * Testing Trait for building Resolve requests
 */
trait ResolveRequestsHttp {

  def resolve2(id: PackageId): HttpRequest =
    resolve(id.name.get, id.version.get)

  def resolve(pname: String, pversion: String): HttpRequest =
    Get(Resource.uri("resolve", pname, pversion))

}

trait ResolveRequests extends
  ResolveRequestsHttp with
  Matchers with
  Namespaces { self: ScalatestRouteTest =>

  def resolveOK(pname: String, pversion: String, deviceIds: Seq[Device.DeviceId])(implicit route: Route): Unit = {

    resolve(pname, pversion) ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Map[Device.DeviceId, List[PackageId]]] shouldBe
        ResolveFunctions.makeFakeDependencyMap(PackageId(Refined.unsafeApply(pname), Refined.unsafeApply(pversion)),
          deviceIds.map(Device(defaultNs, _)))
    }
  }

}
