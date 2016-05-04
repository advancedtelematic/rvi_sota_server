/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.test

import eu.timepit.refined.api.Refined
import org.scalacheck.{Arbitrary, Gen}
import org.genivi.sota.data.Namespaces
import org.genivi.sota.resolver.devices.Device


object DeviceGenerators {

  import Device._

  val genDeviceId: Gen[DeviceId] = for {
    id <- Gen.identifier
  } yield Refined.unsafeApply(id)

  val genDevice: Gen[Device] = for {
    deviceId <- genDeviceId
  } yield Device(Namespaces.defaultNs, deviceId)

  implicit lazy val arbDeviceId: Arbitrary[DeviceId] = Arbitrary(genDeviceId)
  implicit lazy val arbDevice: Arbitrary[Device] = Arbitrary(genDevice)

}
