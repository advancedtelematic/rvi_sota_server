/**
  * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
  * License: MPL-2.0
  */
package org.genivi.sota.device_registry

import akka.http.scaladsl.model.StatusCodes._
import io.circe.generic.auto._
import org.genivi.sota.data.{CredentialsType, Device, DeviceT, Uuid}
import org.genivi.sota.device_registry.PublicCredentialsResource.FetchPublicCredentials
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.scalacheck.{Arbitrary, Gen}

class PublicCredentialsResourceSpec extends ResourcePropSpec {
  import Device._

  val genCredentialsType: Gen[CredentialsType.CredentialsType] = Gen.oneOf(CredentialsType.values.toSeq)

  implicit lazy val arbCredentialsType: Arbitrary[CredentialsType.CredentialsType] = Arbitrary(genCredentialsType)

  property("GET requests fails on non-existent device") {
    forAll { (uuid:Uuid) =>
      fetchPublicCredentials(uuid) ~> route ~> check { status shouldBe NotFound }
    }
  }

  property("GET request after PUT yields same credentials") {
    forAll { (deviceId: DeviceId, creds: Array[Byte]) =>
      val uuid = updatePublicCredentialsOk(deviceId, creds)

      fetchPublicCredentialsOk(uuid) shouldBe creds
    }
  }

  property("PUT uses existing uuid if device exists") {
    forAll { (devId: DeviceId, mdevT: DeviceT, creds: Array[Byte]) =>
      val devT = mdevT.copy(deviceId = Some(devId))
      val uuid = createDeviceOk(devT)
      uuid shouldBe updatePublicCredentialsOk(devId, creds)

      // updatePublicCredentials didn't change the device
      fetchDevice(uuid) ~> route ~> check {
        status shouldBe OK
        val dev = responseAs[Device]
        dev.deviceName shouldBe devT.deviceName
        dev.deviceId   shouldBe devT.deviceId
        dev.deviceType shouldBe devT.deviceType
      }
    }
  }

  property("Latest PUT is the one that wins") {
    forAll { (deviceId: DeviceId, creds1: Array[Byte], creds2: Array[Byte]) =>
      val uuid = updatePublicCredentialsOk(deviceId, creds1)
      updatePublicCredentialsOk(deviceId, creds2)

      fetchPublicCredentialsOk(uuid) shouldBe creds2
    }
  }

  property("Type of credentials is set correctly") {
    forAll { (deviceId: DeviceId, mdevT: DeviceT, creds: String, cType: CredentialsType.CredentialsType) =>
      val devT = mdevT.copy(deviceId = Some(deviceId), credentials = Some(creds), credentialsType = Some(cType))
      val uuid = createDeviceWithCredentials(devT) ~> route ~> check {
        status shouldBe OK
        responseAs[Uuid]
      }

      fetchPublicCredentials(uuid) ~> route ~> check {
        status shouldBe OK
        val dev = responseAs[FetchPublicCredentials]

        dev.uuid shouldBe uuid
        dev.credentialsType shouldBe cType
        dev.credentials shouldBe creds
      }
    }
  }
}
