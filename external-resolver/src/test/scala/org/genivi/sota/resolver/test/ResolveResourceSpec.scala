package org.genivi.sota.resolver.test


import akka.http.scaladsl.model.StatusCodes
import eu.timepit.refined.api.Refined
import io.circe.generic.auto._
import org.genivi.sota.data.Device.{DeviceId, DeviceName}
import org.genivi.sota.data.{Device, DeviceT, PackageId, Uuid}
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.rest.{ErrorCodes, ErrorRepresentation}
import org.scalatest.concurrent.ScalaFutures
import Device._

import scala.concurrent.Future


/**
 * Spec for testing Resolver REST actions
 */
class ResolveResourceSpec extends ResourceWordSpec with ScalaFutures {

  val pkgName = "resolvePkg"

  lazy val testDevices: Seq[(DeviceT, Uuid)] = {
    Future.sequence {
      (0 to 4).map { i =>
        val d = DeviceT(DeviceName(s"Name $i"),
          Some(DeviceId(s"${i}0RES0LVEV1N12345")))

        deviceRegistry.createDevice(d).map((d, _))
      }
    }.futureValue
  }

  "Resolve resource" should {
    //noinspection ZeroIndexToHead
    "support filtering by hardware components on VIN" in {

      // Delete the previous filter and add another one which uses
      // has_component instead.

      val (_, id0) = testDevices(0)
      val (_, id1) = testDevices(1)
      val (_, id2) = testDevices(2)

      deletePackageFilterOK(pkgName, "0.0.1", "1xfilter")
      addComponentOK(Refined.unsafeApply("jobby0"), "nice")
      addComponentOK(Refined.unsafeApply("jobby1"), "nice")
      installComponentOK(id0, Refined.unsafeApply("jobby0"))
      installComponentOK(id1, Refined.unsafeApply("jobby0"))
      installComponentOK(id2, Refined.unsafeApply("jobby1"))
      addFilterOK("components", s"""has_component "^.*y0"""")
      addPackageFilterOK(pkgName, "0.0.1", "components")

      resolveOK(pkgName, "0.0.1", List(id0, id1))
    }

    "return no VINs if the filter is trivially false" in {

      // Add trivially false filter.
      addFilterOK("falsefilter", "FALSE")
      addPackageFilterOK(pkgName, "0.0.1", "falsefilter")

      resolveOK(pkgName, "0.0.1", List())
    }

    "fail if a non-existing package name is given" in {

      resolve(defaultNs, "resolvePkg2", "0.0.1") ~> route ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[ErrorRepresentation].code shouldBe ErrorCodes.MissingEntity
      }
    }

    "fail if a non-existing package version is given" in {

      resolve(defaultNs, pkgName, "0.0.2") ~> route ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[ErrorRepresentation].code shouldBe ErrorCodes.MissingEntity
      }
    }

    //noinspection ZeroIndexToHead
    "return a string that the core server can parse" in {
      val (_, id0) = testDevices(0)
      val (_, id1) = testDevices(1)

      deletePackageFilter(pkgName, "0.0.1", "falseFilter") ~> route ~> check {
        status shouldBe StatusCodes.OK
        resolve(defaultNs, pkgName, "0.0.1") ~> route ~> check {
          status shouldBe StatusCodes.OK

          responseAs[Map[Uuid, Set[PackageId]]] shouldBe
            Map(id0 ->
              Set(PackageId(Refined.unsafeApply(pkgName), Refined.unsafeApply("0.0.1"))),
              id1 ->
                Set(PackageId(Refined.unsafeApply(pkgName), Refined.unsafeApply("0.0.1"))))

        }
      }
    }
  }
}
