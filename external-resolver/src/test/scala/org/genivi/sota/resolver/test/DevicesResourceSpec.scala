/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.test

import akka.http.scaladsl.model.StatusCodes
import eu.timepit.refined.{refineMV, refineV}
import eu.timepit.refined.api.Refined
import io.circe.generic.auto._
import org.genivi.sota.data.{Namespaces, PackageId}
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.resolver.common.Errors.Codes
import org.genivi.sota.resolver.devices.Device
import org.genivi.sota.resolver.packages.{Package, PackageFilter}
import org.genivi.sota.resolver.packages.Package._
import org.genivi.sota.resolver.components.Component
import org.genivi.sota.rest.{ErrorCodes, ErrorRepresentation}
import org.scalacheck._


/**
 * Spec for Device REST actions
 */
class DevicesResourcePropSpec extends ResourcePropSpec {

  import PackageGenerators._
  import DeviceGenerators._

  val devices = "devices"

  property("Devices resource should create new resource on PUT request") {
    forAll { devices: Device =>
      addDeviceOK(devices.id)
    }
  }

  // TODO: re-enable test
  // property("Invalid devices are rejected") {
  //   forAll(genInvalidDevice) { devices: Device =>
  //     addDevice(devices.id) ~> route ~> check {
  //       status shouldBe StatusCodes.BadRequest
  //     }
  //   }
  // }

  property("PUTting the same deviceId twice updates it") {
    forAll { devices: Device  =>
      addDeviceOK(devices.id)
      addDeviceOK(devices.id)
    }
  }

  import org.scalacheck.Shrink
  implicit val noShrink: Shrink[List[Package]] = Shrink.shrinkAny

  property("fail to set installed packages if deviceId does not exist") {
    // use UUIDs instead of generated ids, in order to minimize the collision with previously generated values
    forAll(Gen.uuid, Gen.nonEmptyListOf(genPackage)) { (deviceId, packages) =>
      Put( Resource.uri(devices, deviceId.toString, "packages"),  packages.map( _.id )) ~> route ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[ErrorRepresentation].code shouldBe Codes.MissingDevice
      }
    }
  }

  property("updates installed packages even if some of them does not exist") {
    val stateGen : Gen[(Set[Package], Set[Package])] = for {
      beforeUpdate <- Gen.nonEmptyContainerOf[Set, Package](genPackage)
      added        <- Gen.nonEmptyContainerOf[Set, Package](genPackage)
      nonExistentAdded <- Gen.nonEmptyContainerOf[Set, Package](genPackage)
      removed      <- Gen.someOf(beforeUpdate)
    } yield (beforeUpdate ++ added, beforeUpdate -- removed ++ added ++ nonExistentAdded)

    forAll(genDeviceId, stateGen) { (deviceId, state) =>
      val (availablePackages, update) = state
      addDeviceOK(deviceId)
      availablePackages.foreach( p => addPackageOK(p.id.name.get, p.id.version.get, p.description, p.vendor) )
      Put( Resource.uri(devices, deviceId.get, "packages"),  update.map( _.id )) ~> route ~> check {
        status shouldBe StatusCodes.NoContent
      }
    }
  }

}

/**
 * Word Spec for Device REST actions
 */
class DevicesResourceWordSpec extends ResourceWordSpec with Namespaces {

  val devices = "devices"

  val deviceId  : Device.DeviceId = refineV[Device.ValidDeviceId]("V1N00LAM0FAU2DEEP").right.get
  val device    : Device    = Device(defaultNs, deviceId)
  val deviceId2 : Device.DeviceId = refineV[Device.ValidDeviceId]("XAPABEPA123456789").right.get
  val device2   : Device    = Device(defaultNs, deviceId2)

  "Device resource" should {

    "create a new resource on PUT request" in {
      addDeviceOK(deviceId)
      addDeviceOK(deviceId2)
    }

    // TODO: re-enable tests
    // "not accept too long VINs" in {
    //   addDevice(Refined.unsafeApply(deviceId.get + "1")) ~> route ~> check {
    //     status shouldBe StatusCodes.BadRequest
    //     responseAs[ErrorRepresentation].code shouldBe ErrorCodes.InvalidEntity
    //   }
    // }

    // TODO: re-enable tests
    // "not accept too short VINs" in {
    //   addDevice(Refined.unsafeApply(deviceId.get.drop(1))) ~> route ~> check {
    //     status shouldBe StatusCodes.BadRequest
    //     responseAs[ErrorRepresentation].code shouldBe ErrorCodes.InvalidEntity
    //   }
    // }

    // TODO: re-enable tests
    // "not accept VINs which aren't alpha num" in {
    //   addDevice(Refined.unsafeApply(deviceId.get.drop(1) + "!")) ~> route ~> check {
    //     status shouldBe StatusCodes.BadRequest
    //     responseAs[ErrorRepresentation].code shouldBe ErrorCodes.InvalidEntity
    //   }
    // }

    "allow duplicate entries" in {
      addDeviceOK(deviceId)
    }

    "list all devices on a GET request" in {
      listDevices ~> route ~> check {
        responseAs[Seq[Device]] shouldBe List(device, device2)
      }
    }

    "list a specific device on GET /devices/:deviceId or fail if it doesn't exist" in {
      Get(Resource.uri("devices", deviceId.get)) ~> route ~> check {
        responseAs[Device] shouldBe device
      }
      Get(Resource.uri("devices", deviceId.get.drop(1) + "1")) ~> route ~> check {
        responseAs[ErrorRepresentation].code shouldBe Codes.MissingDevice
      }
    }

    "return a 404 when deleting a device which doesn't exist" in {
      Delete(Resource.uri(devices, "123456789N0TTHERE")) ~> route ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "delete a device" in {
      val deviceId = refineV[Device.ValidDeviceId]("12345678901234V1N").right.get: Device.DeviceId
      addDeviceOK(deviceId)
      Delete(Resource.uri(devices, deviceId.get)) ~> route ~> check {
        status shouldBe StatusCodes.OK
      }
    }

    "delete a device and its installPackages" in {
      val deviceId: Device.DeviceId = refineV[Device.ValidDeviceId]("1234567890THERV1N").right.get
      addDeviceOK(deviceId)
      addPackageOK("halflife", "3.0.0", None, None)
      installPackageOK(deviceId, "halflife", "3.0.0")
      addPackageOK("halflife", "4.0.0", None, None)
      installPackageOK(deviceId, "halflife", "4.0.0")
      Delete(Resource.uri(devices, deviceId.get)) ~> route ~> check {
        status shouldBe StatusCodes.OK
      }
    }

    "delete a device and its installComponents" in {
      val deviceId  = refineV[Device.ValidDeviceId]("1234567890THERV1N").right.get: Device.DeviceId
      val comp = refineMV("ashtray")          : Component.PartNumber
      addDeviceOK(deviceId)
      addComponentOK(comp, "good to have")
      installComponentOK(deviceId, comp)
      Delete(Resource.uri(devices, deviceId.get)) ~> route ~> check {
        status shouldBe StatusCodes.OK
      }
    }

    /*
     * Tests related to installing packages on devices.
     */

    "install a package on a device on PUT request to /devices/:deviceId/package/:packageName/:packageVersion" in {
      addPackageOK("apa", "1.0.1", None, None)
      installPackageOK(deviceId, "apa", "1.0.1")
    }

    "fail to install a package on a non-existing device" in {
      installPackage(Refined.unsafeApply(deviceId.get.drop(1) + "B"), "bepa", "1.0.1") ~> route ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[ErrorRepresentation].code shouldBe Codes.MissingDevice
      }
    }

    "fail to install a non-existing package on a device" in {
      installPackage(deviceId, "bepa", "1.0.1") ~> route ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[ErrorRepresentation].code shouldBe Codes.PackageNotFound
      }
    }

    "list installed packages on a device on GET request to /devices/:deviceId/package" in {
      Get(Resource.uri(devices, deviceId.get, "package")) ~> route ~> check {
        status shouldBe StatusCodes.OK
        val name: PackageId.Name = Refined.unsafeApply("apa")
        val version: PackageId.Version = Refined.unsafeApply("1.0.1")
        responseAs[Seq[PackageId]] shouldBe List(PackageId(name, version))
      }
    }

    "fail to list installed packages on devices that don't exist" in {
      Get(Resource.uri(devices, deviceId.get.drop(1) + "B", "package")) ~> route ~> check {
        status shouldBe StatusCodes.NotFound
      }

    }

    "uninstall a package on a device on DELETE request to /devices/:deviceId/package/:packageName/:packageVersion" in {
      Delete(Resource.uri(devices, deviceId.get, "package", "apa", "1.0.1")) ~> route ~> check {
        status shouldBe StatusCodes.OK
      }
      Get(Resource.uri(devices, deviceId.get, "package")) ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[Seq[PackageId]] shouldBe List()
      }
    }

    "fail to uninstall a package from a non-existing device" in {
      Delete(Resource.uri(devices, deviceId.get.drop(1) + "B", "package", "apa", "1.0.1")) ~> route ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[ErrorRepresentation].code shouldBe Codes.MissingDevice
      }
    }

    "fail to uninstall a package that isn't installed on a device" in {
      Delete(Resource.uri(devices, deviceId.get, "package", "bepa", "1.0.1")) ~> route ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[ErrorRepresentation].code shouldBe Codes.PackageNotFound
      }
    }

    "list all devices that have a specific package installed on GET request" +
        " to /devices?packageName=:packageName&packageVersion=:packageVersion" in {
      installPackageOK(deviceId, "apa", "1.0.1")
      Get(Resource.uri(devices) + "?packageName=apa&packageVersion=1.0.1") ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[Seq[Device]] shouldBe List(device)
      }
    }

    "return the empty list of devices when the package does not exist" in {
      installPackageOK(deviceId, "apa", "1.0.1")
      Get(Resource.uri(devices) + "?packageName=apa&packageVersion=0.0.0") ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[Seq[Device]] shouldBe List()
      }
    }

    "fail if package parameters ain't provided properly" in {
      installPackageOK(deviceId, "apa", "1.0.1")
      Get(Resource.uri(devices) + "?packageName=&packageVersion=") ~> route ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

  }

  /*
   * Tests related to installing components on devices.
   */

  "install component on device on PUT /devices/:deviceId/component/:partNumber" in {
    addComponentOK(Refined.unsafeApply("jobby0"), "nice")
    installComponentOK(deviceId, Refined.unsafeApply("jobby0"))
  }

  "list components on a device on GET /devices/:deviceId/component" in {
    Get(Resource.uri(devices, deviceId.get, "component")) ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[Component.PartNumber]] shouldBe List(Refined.unsafeApply("jobby0"))
    }
  }

  "list devices that have a specific component on GET /devices?component=:partNumber" in {
    Get(Resource.uri(devices) + "?component=jobby0") ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[Device]] shouldBe List(device)
    }
    Get(Resource.uri(devices) + "?component=jobby1") ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[Device]] shouldBe List()
    }
  }

  "fail to list devices that have a specific empty or malformated component installed" in {
    Get(Resource.uri(devices) + "?component=") ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  "fail to delete components that are installed on devices" in {
    Delete(Resource.uri("components", "jobby0")) ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe Codes.ComponentInstalled
    }
  }

}
