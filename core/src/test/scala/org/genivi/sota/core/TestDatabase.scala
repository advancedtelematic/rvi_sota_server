/**
  * Copyright: Copyright (C) 2015, Jaguar Land Rover
  * License: MPL-2.0
  */
package org.genivi.sota.core

import com.typesafe.config.ConfigFactory
import org.flywaydb.core.Flyway
import org.genivi.sota.core.common.NamespaceDirective
import org.genivi.sota.core.data.{Package, UpdateSpec}
import org.genivi.sota.core.db.{Packages, UpdateRequests, UpdateSpecs, Devices}
import org.genivi.sota.data.Namespace.Namespace
import org.genivi.sota.data.{Device, DeviceGenerators}
import org.genivi.sota.db.SlickExtensions
import org.joda.time.DateTime
import org.scalacheck.Gen
import org.scalatest.concurrent.{AbstractPatienceConfiguration, PatienceConfiguration}
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Suite}
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import slick.driver.MySQLDriver.api._


/*
 * Helper object to configure test database for specs
 */
object TestDatabase {

  def resetDatabase( databaseName: String ) = {
    val dbConfig = ConfigFactory.load().getConfig(databaseName)
    val url = dbConfig.getString("url")
    val user = dbConfig.getConfig("properties").getString("user")
    val password = dbConfig.getConfig("properties").getString("password")

    val flyway = new Flyway
    flyway.setDataSource(url, user, password)
    flyway.setLocations("classpath:db.migration")
    flyway.clean()
    flyway.migrate()
  }
}

trait DefaultDBPatience {
  self: PatienceConfiguration =>

  override implicit def patienceConfig = PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))
}

trait DatabaseSpec extends BeforeAndAfterAll {
  self: Suite =>

  import eu.timepit.refined.auto._

  private val databaseId = "test-database"

  lazy val db = Database.forConfig(databaseId)

  private lazy val config = ConfigFactory.load()

  val defaultNamespace: Namespace =
    NamespaceDirective.configNamespace(config).getOrElse("default-test-ns")

  override def beforeAll() {
    TestDatabase.resetDatabase(databaseId)
    super.beforeAll()
  }

  override def afterAll() {
    db.close()
    super.afterAll()
  }
}

trait UpdateResourcesDatabaseSpec {
  self: DatabaseSpec =>

  import Generators._

  def createUpdateSpecFor(device: Device, creationTime: DateTime = DateTime.now)
                         (implicit ec: ExecutionContext): DBIO[(Package, UpdateSpec)] = {
    val (packageModel, updateSpec) = genUpdateSpecFor(device).sample.get

    val dbIO = DBIO.seq(
      Packages.create(packageModel),
      UpdateRequests.persist(updateSpec.request.copy(creationTime = creationTime)),
      UpdateSpecs.persist(updateSpec)
    )

    dbIO.map(_ => (packageModel, updateSpec))
  }

  def createUpdateSpec()(implicit ec: ExecutionContext): Future[(Package, Device, UpdateSpec)] = {
    val device = DeviceGenerators.genDevice.sample.get

    val dbIO = for {
      _ <- Devices.create(device)
      (packageModel, updateSpec) <- createUpdateSpecFor(device)
    } yield (packageModel, device, updateSpec)

    db.run(dbIO)
  }
}

trait DeviceDatabaseSpec {
  self: DatabaseSpec =>

  def createDevice()(implicit ec: ExecutionContext): Future[Device] = {
    val device = DeviceGenerators.genDevice.sample.get
    db.run(Devices.create(device))
  }
}
