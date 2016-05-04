/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.Unmarshaller._
import eu.timepit.refined.api.Refined
import io.circe.generic.auto._
import io.circe.syntax._
import java.util.UUID
import org.genivi.sota.core.data.{DeviceStatus, DeviceUpdateStatus}
import org.genivi.sota.core.jsonrpc.HttpTransport
import org.genivi.sota.core.rvi._
import org.genivi.sota.data.Device
import org.genivi.sota.data.Namespaces
import org.genivi.sota.marshalling.CirceMarshallingSupport
import org.scalacheck.Gen
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.PropertyChecks
import org.scalatest.time.{Millis, Seconds, Span}
import slick.driver.MySQLDriver.api._


/**
 * Spec tests for device REST actions
 */
class DeviceResourceSpec extends PropSpec
  with PropertyChecks
  with Matchers
  with ScalatestRouteTest
  with ScalaFutures
  with DatabaseSpec
  with DeviceDatabaseSpec
  with Namespaces {

  import CirceMarshallingSupport._
  import Generators._
  import org.genivi.sota.data.DeviceGenerators._
  import org.genivi.sota.data.PackageIdGenerators._

  val rviUri = Uri(system.settings.config.getString( "rvi.endpoint" ))
  val serverTransport = HttpTransport( rviUri )
  implicit val rviClient = new JsonRpcRviClient( serverTransport.requestTransport, system.dispatcher)

  val fakeResolver = new FakeExternalResolver()

  lazy val service = new DevicesResource(db, rviClient, fakeResolver)

  val BasePath = Path("/devices")

  implicit val patience = PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))

  implicit val _db = db

  def resourceUri( pathSuffix : String ) : Uri = {
    Uri.Empty.withPath(BasePath / pathSuffix)
  }

  def deviceUri(deviceUuid: Device.Id) = Uri.Empty.withPath( BasePath / deviceUuid.toString )

  property( "create new device" ) {
    forAll { (device: Device) =>
      Post(BasePath.toString, device) ~> service.route ~> check {
        status shouldBe StatusCodes.NoContent
      }
    }
  }

  import org.genivi.sota.data.Vehicle

  val tooLongVin = for {
    n <- Gen.choose(18, 100)
    vin <- Gen.listOfN(n, Gen.alphaNumChar)
  } yield vin.mkString

  val tooShortVin = for {
    n <- Gen.choose(1, 16)
    vin <- Gen.listOfN(n, Gen.alphaNumChar)
  } yield vin.mkString

  val VehicleWithIllegalVin : Gen[Vehicle] = for {
    vin <- Gen.oneOf(tooLongVin, tooShortVin)
  } yield Vehicle(defaultNs, Refined.unsafeApply(vin))

  // TODO: re-enable failing test
  // property( "reject illegal vins" ) {
  //   forAll( VehicleWithIllegalVin ) { device =>
  //     Put( deviceUri(device.uuid), device ) ~> Route.seal(service.route) ~> check {
  //       status shouldBe StatusCodes.BadRequest
  //     }
  //   }
  // }

  property("Multiple POST requests with the same device uuid are allowed") {
    forAll { (device: Device) =>
      val request = Post(BasePath.toString, device) ~> service.route
      request ~> check { status shouldBe StatusCodes.NoContent }
      request ~> check { status shouldBe StatusCodes.NoContent }
    }
  }

  property("search with status=true returns current status for a device") {
    whenReady(createDevice()) { _ =>
      val url = Uri.Empty
        .withPath(BasePath)
        .withQuery(Uri.Query("status" -> "true"))

      Get(url) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        val parsedResponse = responseAs[Seq[DeviceUpdateStatus]].headOption

        parsedResponse.flatMap(_.lastSeen) shouldNot be(defined)
        parsedResponse.map(_.status) should contain(DeviceStatus.NotSeen)
      }
    }
  }
}
