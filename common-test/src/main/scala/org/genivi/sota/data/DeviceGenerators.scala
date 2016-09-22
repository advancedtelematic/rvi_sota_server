/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.data

import eu.timepit.refined.api.Refined
import org.scalacheck.{Arbitrary, Gen}
import java.time.Instant

trait DeviceGenerators {

  import Arbitrary._
  import Device._
  import UuidGenerator._

  val genDeviceName: Gen[DeviceName] = for {
    name <- arbitrary[String]
  } yield DeviceName(name)

  val genDeviceId: Gen[DeviceId] = for {
    id <- Gen.identifier
  } yield DeviceId(id)

  val genDeviceType: Gen[DeviceType] = for {
    t <- Gen.oneOf(DeviceType.values.toSeq)
  } yield t

  val genLastSeen: Gen[Instant] = for {
    millis <- Gen.chooseNum[Long](0, 10000000000000L)
  } yield Instant.ofEpochMilli(millis)

  def genDeviceWith(deviceNameGen: Gen[DeviceName], deviceIdGen: Gen[DeviceId]): Gen[Device] = for {
    uuid <- arbitrary[Uuid]
    name <- deviceNameGen
    deviceId <- Gen.option(deviceIdGen)
    deviceType <- genDeviceType
    lastSeen <- Gen.option(genLastSeen)
  } yield Device(Namespaces.defaultNs, uuid, name, deviceId, deviceType, lastSeen)

  val genDevice: Gen[Device] = genDeviceWith(genDeviceName, genDeviceId)

  def genDeviceTWith(deviceNameGen: Gen[DeviceName], deviceIdGen: Gen[DeviceId]): Gen[DeviceT] = for {
    name <- deviceNameGen
    deviceId <- Gen.option(deviceIdGen)
    deviceType <- genDeviceType
  } yield DeviceT(name, deviceId, deviceType)

  val genDeviceT: Gen[DeviceT] = genDeviceTWith(genDeviceName, genDeviceId)

  def genConflictFreeDeviceTs(): Gen[Seq[DeviceT]] =
    genConflictFreeDeviceTs(arbitrary[Int].sample.get)

  def genConflictFreeDeviceTs(n: Int): Gen[Seq[DeviceT]] = {
    val namesG = Gen.listOfN(n, arbitrary[DeviceName]).retryUntil { l =>
      l.distinct.length == l.length }

    val idsG = Gen.listOfN(n, arbitrary[DeviceId]).retryUntil { l =>
      l.distinct.length == l.length }

    for {
      names <- namesG
      ids <- idsG
      (n, id) <- names.zip(ids)
    } yield genDeviceTWith(n, id).sample.get
  }

  implicit lazy val arbDeviceName: Arbitrary[DeviceName] = Arbitrary(genDeviceName)
  implicit lazy val arbDeviceId: Arbitrary[DeviceId] = Arbitrary(genDeviceId)
  implicit lazy val arbDeviceType: Arbitrary[DeviceType] = Arbitrary(genDeviceType)
  implicit lazy val arbLastSeen: Arbitrary[Instant] = Arbitrary(genLastSeen)
  implicit lazy val arbDevice: Arbitrary[Device] = Arbitrary(genDevice)
  implicit lazy val arbDeviceT: Arbitrary[DeviceT] = Arbitrary(genDeviceT)

}

object DeviceGenerators extends DeviceGenerators

object InvalidDeviceGenerators extends DeviceGenerators with DeviceIdGenerators {
  val genInvalidVehicle: Gen[Device] = for {
  // TODO: for now, just generate an invalid VIN with a valid namespace
    deviceId <- genInvalidDeviceId
    d <- genDevice
  } yield d.copy(deviceId = Option(deviceId), namespace = Namespaces.defaultNs)

  def getInvalidVehicle: Device = genInvalidVehicle.sample.getOrElse(getInvalidVehicle)
}
