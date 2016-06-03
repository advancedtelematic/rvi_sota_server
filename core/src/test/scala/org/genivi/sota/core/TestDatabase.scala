/**
  * Copyright: Copyright (C) 2015, Jaguar Land Rover
  * License: MPL-2.0
  */
package org.genivi.sota.core

import akka.http.scaladsl.util.FastFuture
import com.typesafe.config.{Config, ConfigFactory}
import org.genivi.sota.common.IDeviceRegistry
import org.genivi.sota.core.data.{Package, UpdateRequest, UpdateSpec}
import org.genivi.sota.core.db.{Packages, UpdateRequests, UpdateSpecs}
import org.genivi.sota.data.{Device, DeviceT}
import scala.concurrent.ExecutionContext
import slick.driver.MySQLDriver.api._
import org.genivi.sota.data.{Vehicle, VehicleGenerators}
import org.genivi.sota.datatype.NamespaceDirective
import scala.concurrent.Future
import slick.driver.MySQLDriver.api._

object NamespaceSpec {
  import eu.timepit.refined.auto._
  import eu.timepit.refined.string._
  import org.genivi.sota.datatype.Namespace._

  lazy val defaultNamespace: Namespace = {
    val config = ConfigFactory.load()
    NamespaceDirective.configNamespace(config).getOrElse("default-test-ns")
  }
}


trait UpdateResourcesDatabaseSpec {
  self: DatabaseSpec =>

  import Generators._

  def createUpdateSpecFor(vehicle: Vehicle, installPos: Int = 0, withMillis: Long = -1)
                         (implicit ec: ExecutionContext): DBIO[(Package, UpdateSpec)] = {
    val (packageModel, updateSpec0) = genUpdateSpecFor(vehicle, withMillis).sample.get
    val updateSpec = updateSpec0.copy(installPos = installPos)

    val dbIO = DBIO.seq(
      Packages.create(packageModel),
      UpdateRequests.persist(updateSpec.request),
      UpdateSpecs.persist(updateSpec)
    )

    dbIO.map(_ => (packageModel, updateSpec))
  }

  def createUpdateSpecAction()(implicit ec: ExecutionContext): DBIO[(Package, Vehicle, UpdateSpec)] = {
    val vehicle = VehicleGenerators.genVehicle.sample.get

    for {
      (packageModel, updateSpec) <- createUpdateSpecFor(vehicle)
    } yield (packageModel, vehicle, updateSpec)
  }

  def createUpdateSpec()(implicit ec: ExecutionContext): Future[(Package, Vehicle, UpdateSpec)] = {
    db.run(createUpdateSpecAction())
  }
}

trait VehicleDatabaseSpec {
  self: DatabaseSpec =>

  import Device._

  def createVehicle(deviceRegistry: IDeviceRegistry)(implicit ec: ExecutionContext): Future[Vehicle] = {
    createDevice(deviceRegistry).map(_._2)
  }

  def createDevice(deviceRegistry: IDeviceRegistry)(implicit ec: ExecutionContext): Future[(Id, Vehicle)] = {
    val vehicle = VehicleGenerators.genVehicle.sample.get
    val f = deviceRegistry.createDevice(DeviceT(DeviceName(vehicle.vin.get),
      Some(DeviceId(vehicle.vin.get)),
      DeviceType.Vehicle))

    f.map(id => (id,vehicle))
  }
}
