/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.testkit.ScalatestRouteTest
import eu.timepit.refined.api.Refined
import eu.timepit.refined.refineMV
import io.circe.generic.auto._
import org.genivi.sota.core.common.NamespaceDirective
import org.genivi.sota.core.db.Devices
import org.genivi.sota.core.jsonrpc.HttpTransport
import org.genivi.sota.core.rvi.JsonRpcRviClient
import org.genivi.sota.data.Device
import org.genivi.sota.data.Namespace._
import org.genivi.sota.marshalling.CirceMarshallingSupport
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.{Matchers, WordSpec}
import scala.concurrent.Await
import slick.driver.MySQLDriver.api._


/**
 * WordSpec for VIN REST actions
 */
class DeviceResourceWordSpec extends WordSpec
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfterAll {

  import CirceMarshallingSupport._
  import org.genivi.sota.data.DeviceGenerators._
  import Arbitrary._

  val databaseName = "test-database"
  val db = Database.forConfig(databaseName)

  val rviUri = Uri(system.settings.config.getString( "rvi.endpoint" ))
  val serverTransport = HttpTransport( rviUri )
  implicit val rviClient = new JsonRpcRviClient( serverTransport.requestTransport, system.dispatcher)

  lazy val service = new DevicesResource(db, rviClient, new FakeExternalResolver())

  val testNs: Namespace = Refined.unsafeApply("default")
  val testDevices = Gen.listOfN(3, genDevice).sample.get
  val testDevice = testDevices.head.uuid
  val missingDevice = arbitrary[Device.Id].sample.get.toString // TODO: check for collision? – should not be necessary

  def arbSubstr(s: String): Gen[String] = for {
    i <- Gen.choose(0, s.length)
    j <- Gen.choose(i, s.length)
  } yield s.substring(i, j)

  override def beforeAll() : Unit = {
    TestDatabase.resetDatabase( databaseName )
    import scala.concurrent.duration._
    Await.ready(db.run(DBIO.seq(testDevices.map(d => Devices.create(d)): _*)), 2.seconds)
  }

  val devicesUri  = Uri( "/devices" )

  "Device resource" should {
    "list resources on GET request" in {
      Get( devicesUri ) ~> service.route ~> check {
        assert(status === StatusCodes.OK)
        val devices = responseAs[Seq[Device]]
        assert(devices.nonEmpty)
        assert(devices.exists(d => d.uuid === testDevice))
        assert(devices.length === 3)
      }
    }

    "list resource on GET :deviceUuid request" in {
      Get(devicesUri + "/" + testDevice.toString) ~> service.route ~> check {
        assert(status === StatusCodes.OK)
      }
    }

    "return a 404 on GET :deviceUuid that doesn't exist" in {
      Get(devicesUri + "/" + missingDevice) ~> service.route ~> check {
        assert(status === StatusCodes.NotFound)
      }
    }

    "return a list of packages installed on a devices" in {
      Get(devicesUri + "/" + testDevice.toString + "/queued") ~> service.route ~> check {
        assert(status === StatusCodes.OK)
      }
    }

    "initiate a getpackages message to a devices" in {
      Put(devicesUri + "/" + testDevice.toString + "/sync") ~> service.route ~> check {
        assert(status === StatusCodes.NoContent)
      }
    }

    // TODO: regex with UUIDs doesn't make much sense
    "filter list of devices by regex" in {
      val substr = arbSubstr(testDevice.toString).sample.get
      Get(devicesUri + "?regex=" + substr) ~> service.route ~> check {
        assert(status === StatusCodes.OK)
        val devices = responseAs[Seq[Device]]
        assert(devices.length === testDevices.filter(_.uuid.toString.contains(substr)).length)
      }
    }

    // "filter list of devices by regex 'WW$'" in {
    //   Get( devicesUri + "?regex=WW$" ) ~> service.route ~> check {
    //     assert(status === StatusCodes.OK)
    //     val devices = responseAs[Seq[Device]]
    //     assert(devices.length === 1)
    //   }
    // }

    "delete a devices" in {
      Delete( devicesUri + "/" + testDevice.toString) ~> service.route ~> check {
        assert(status === StatusCodes.OK)
      }
    }

    "return a 404 when deleting a non existing devices" in {
      Delete( devicesUri + "/" + missingDevice) ~> service.route ~> check {
        assert(status === StatusCodes.NotFound)
      }
    }
  }

  override def afterAll() : Unit = {
    system.terminate()
    db.close()
  }

}
