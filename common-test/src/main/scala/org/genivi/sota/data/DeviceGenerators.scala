/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.data

import java.util.UUID
import org.scalacheck.{Arbitrary, Gen}

object DeviceGenerators {

  implicit val DeviceOrdering: Ordering[Device] =
    new Ordering[Device] {
      override def compare(veh1: Device, veh2: Device): Int =
        veh1.uuid.toString compare veh2.uuid.toString
    }

  val genDevice: Gen[Device] = for {
    uuid <- Gen.uuid
  } yield Device(Namespaces.defaultNs, uuid)

  implicit lazy val arbDevice: Arbitrary[Device] =
    Arbitrary(genDevice)

}
