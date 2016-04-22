/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.data

import eu.timepit.refined.api.Refined
import java.util.UUID
import org.scalacheck.{Arbitrary, Gen}


object DeviceGenerators {

  import Device._

  val genDeviceId: Gen[DeviceId] = for {
    id <- Gen.identifier
  } yield Refined.unsafeApply(id)

  val genDeviceType: Gen[DeviceType] = for {
    v <- Gen.oneOf(DeviceType.values.toList)
  } yield v

  val genDevice: Gen[Device] = for {
    uuid <- Gen.uuid
    deviceId <- genDeviceId
    deviceType <- genDeviceType
  } yield Device(Namespaces.defaultNs, uuid, deviceId, deviceType)

  implicit lazy val arbDeviceId: Arbitrary[DeviceId] = Arbitrary(genDeviceId)
  implicit lazy val arbDeviceType: Arbitrary[DeviceType] = Arbitrary(genDeviceType)
  implicit lazy val arbDevice: Arbitrary[Device] = Arbitrary(genDevice)

  implicit val DeviceOrdering: Ordering[Device] =
    new Ordering[Device] {
      override def compare(d1: Device, d2: Device): Int =
        d1.uuid.toString compare d2.uuid.toString
    }

}
