/**
 * Copyright: Copyright (C) 2016, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.test

import java.time.Instant

import akka.http.scaladsl.model.{StatusCodes, Uri}
import eu.timepit.refined.api.Refined
import io.circe.generic.auto._
import org.genivi.sota.data._
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.resolver.common.Errors.Codes
import org.genivi.sota.resolver.components.Component
import org.genivi.sota.resolver.test.generators.PackageGenerators
import org.genivi.sota.rest.ErrorRepresentation
import org.scalacheck._
import cats.syntax.show._
import org.genivi.sota.resolver.common.InstalledSoftware
import org.genivi.sota.resolver.db.Package
import org.scalatest.concurrent.ScalaFutures

/**
 * Spec for Vehicle REST actions
 */
class DeviceResourcePropSpec extends ResourcePropSpec
    with PackageGenerators with ScalaFutures {

  import DeviceGenerators._

  val devices = "devices"

  import org.scalacheck.Shrink
  implicit val noShrink: Shrink[List[Package]] = Shrink.shrinkAny

  property("installs packages as foreign if they are not native") {
    val packageGen = Gen.nonEmptyContainerOf[Set, PackageId](genPackageId)

    forAll(genDevice, packageGen, minSuccessful(3)) { (device, packageIds) =>
      val id = deviceRegistry.createDevice(device.toResponse).futureValue

      Put(Resource.uri(devices, id.show, "packages"),
        InstalledSoftware(packageIds, Set())) ~> route ~> check {
        status shouldBe StatusCodes.NoContent

        Get(Resource.uri(devices, id.show, "package")) ~> route ~> check {
          status shouldBe StatusCodes.OK
          responseAs[Set[PackageId]] shouldBe packageIds
        }
      }
    }
  }

  property("filters installed packages by regex") {
    val packageGen = Gen.nonEmptyContainerOf[Set, PackageId](genPackageId)

    forAll(genDevice, packageGen, minSuccessful(3)) { (device, packageIds) =>
      val id = deviceRegistry.createDevice(device.toResponse).futureValue

      Put(Resource.uri(devices, id.show, "packages"),
        InstalledSoftware(packageIds, Set())) ~> route ~> check {
        status shouldBe StatusCodes.NoContent

        val query = Uri.Query("regex" -> "doesnotexist")

        Get(Resource.uri(devices, id.show, "package").withQuery(query)) ~> route ~> check {
          status shouldBe StatusCodes.OK
          responseAs[Set[PackageId]] should be(empty)
        }
      }
    }
  }

  property("filters installed packages by partial regex") {
    forAll(genDevice, genPackageId, minSuccessful(3)) { (device, packageId) =>
      val id = deviceRegistry.createDevice(device.toResponse).futureValue

      Put(Resource.uri(devices, id.show, "packages"),
        InstalledSoftware(Set(packageId), Set())) ~> route ~> check {
        status shouldBe StatusCodes.NoContent

        val partialPackageName = packageId.name.get.headOption.map(_.toString).getOrElse(".*")

        val query = Uri.Query("regex" -> partialPackageName)

        Get(Resource.uri(devices, id.show, "package").withQuery(query)) ~> route ~> check {
          status shouldBe StatusCodes.OK
          responseAs[Set[PackageId]] should contain(packageId)
        }
      }
    }
  }

  property("updates foreign packages if already existing") {
    val packageGen = Gen.nonEmptyContainerOf[Set, PackageId](genPackageId)

    forAll(genDevice, packageGen, minSuccessful(3)) { (device, packageIds) =>
      val id = deviceRegistry.createDevice(device.toResponse).futureValue

      val installedSoftware = InstalledSoftware(packageIds, Set())

      Put(Resource.uri(devices, id.show, "packages"), installedSoftware) ~> route ~> check {
        status shouldBe StatusCodes.NoContent

        Put(Resource.uri(devices, id.show, "packages"), installedSoftware) ~> route ~> check {
          status shouldBe StatusCodes.NoContent

          Get(Resource.uri(devices, id.show, "package")) ~> route ~> check {
            status shouldBe StatusCodes.OK
            responseAs[Set[PackageId]] shouldBe packageIds
          }
        }
      }
    }
  }
}

/**
 * Word Spec for Vehicle REST actions
 */
class DevicesResourceWordSpec extends ResourceWordSpec with Namespaces {

  import Device._

  val devices = "devices"

  val uuid = Uuid(Refined.unsafeApply("1f22860a-3ea2-491f-9042-37c98c2d51cd"))

  deviceRegistry.addDevice(Device(Namespaces.defaultNs, uuid, DeviceName("name"), createdAt = Instant.now()))

  /*
   * Tests related to installing components on VINs.
   */

  "install component on Uuid on PUT /devices/:vin/component/:partNumber" in {
    addComponentOK(Refined.unsafeApply("jobby0"), "nice")
    installComponentOK(uuid, Refined.unsafeApply("jobby0"))
  }

  "list components on a Uuid on GET /devices/:vin/component" in {
    Get(Resource.uri(devices, uuid.show, "component")) ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[Component.PartNumber]] shouldBe List(Refined.unsafeApply("jobby0"))
    }
  }

  "fail to delete components that are installed on devices" in {
    Delete(Resource.uri("components", "jobby0")) ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe Codes.ComponentInstalled
    }
  }
}
