/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.device_registry.test

import eu.timepit.refined.api.Refined
import org.scalacheck.{Arbitrary, Gen}
import org.genivi.sota.datatype.Namespaces
import org.genivi.sota.data.{Device, DeviceT}
import org.joda.time.DateTime


object DeviceGenerators {

  import Arbitrary._
  import Device._

  val genId: Gen[Id] = for {
    uuid <- Gen.uuid
  } yield Id(Refined.unsafeApply(uuid.toString))

  val genDeviceName: Gen[DeviceName] = for {
    name <- arbitrary[String]
  } yield DeviceName(name)

  val genDeviceId: Gen[DeviceId] = for {
    id <- Gen.identifier
  } yield DeviceId(id)

  val genDeviceType: Gen[DeviceType] = for {
    t <- Gen.oneOf(DeviceType.values.toSeq)
  } yield t

  val genLastSeen: Gen[DateTime] = for {
    millis <- Gen.chooseNum[Long](0, 10000000000000L)
  } yield (new DateTime(millis))

  def genDeviceWith(deviceNameGen: Gen[DeviceName], deviceIdGen: Gen[DeviceId]): Gen[Device] = for {
    id <- genId
    name <- deviceNameGen
    deviceId <- Gen.option(deviceIdGen)
    deviceType <- genDeviceType
    lastSeen <- Gen.option(genLastSeen)
  } yield Device(Namespaces.defaultNs, id, name, deviceId, deviceType, lastSeen)

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
    val names: Seq[DeviceName] =
      Gen.containerOfN[Seq, DeviceName](n, genDeviceName)
      .suchThat { c => c.distinct.length == c.length }
      .sample.get
    val ids: Seq[DeviceId] =
      Gen.containerOfN[Seq, DeviceId](n, genDeviceId)
      .suchThat { c => c.distinct.length == c.length }
      .sample.get

    val namesG: Seq[Gen[DeviceName]] = names.map(Gen.const(_))
    val idsG: Seq[Gen[DeviceId]] = ids.map(Gen.const(_))

    namesG.zip(idsG).map { case (nameG, idG) =>
      genDeviceTWith(nameG, idG).sample.get
    }
  }

  implicit lazy val arbId: Arbitrary[Id] = Arbitrary(genId)
  implicit lazy val arbDeviceName: Arbitrary[DeviceName] = Arbitrary(genDeviceName)
  implicit lazy val arbDeviceId: Arbitrary[DeviceId] = Arbitrary(genDeviceId)
  implicit lazy val arbDeviceType: Arbitrary[DeviceType] = Arbitrary(genDeviceType)
  implicit lazy val arbLastSeen: Arbitrary[DateTime] = Arbitrary(genLastSeen)
  implicit lazy val arbDevice: Arbitrary[Device] = Arbitrary(genDevice)
  implicit lazy val arbDeviceT: Arbitrary[DeviceT] = Arbitrary(genDeviceT)

}
