package org.genivi.sota.core

import org.genivi.sota.core.data.{UpdateStatus, DeviceStatus, DeviceSearch}
import org.genivi.sota.data.DeviceGenerators
import org.joda.time.DateTime
import org.scalatest.{FunSuite, ShouldMatchers}

class DeviceUpdateStatusSpec extends FunSuite
  with ShouldMatchers {

  import DeviceStatus._

  val device = DeviceGenerators.genDevice.sample.get.copy(lastSeen = Some(DateTime.now))

  test("Error if at least one package is in Failed State") {
    val packages = List(UpdateStatus.Failed, UpdateStatus.Finished)
    val result = DeviceSearch.currentDeviceStatus(device.lastSeen, packages)
    result shouldBe Error
  }

  test("out of date if any package is not finished") {
    val packages = List(UpdateStatus.Pending, UpdateStatus.InFlight)
    val result = DeviceSearch.currentDeviceStatus(device.lastSeen, packages)
    result shouldBe Outdated
  }

  test("up to date if all packages are Finished") {
    val packages = List(UpdateStatus.Finished, UpdateStatus.Finished)
    val result = DeviceSearch.currentDeviceStatus(device.lastSeen, packages)
    result shouldBe UpToDate
  }

  test("not seen if device was never seen") {
    val result = DeviceSearch.currentDeviceStatus(None, List.empty)
    result shouldBe NotSeen
  }
}
