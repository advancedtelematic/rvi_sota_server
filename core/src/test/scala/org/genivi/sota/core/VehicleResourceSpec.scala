/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.Unmarshaller._
import io.circe.generic.auto._
import org.genivi.sota.core.data.{VehicleStatus, VehicleUpdateStatus}
import org.genivi.sota.core.jsonrpc.HttpTransport
import org.genivi.sota.core.rvi._
import org.genivi.sota.data.{Namespaces, Vehicle}
import org.genivi.sota.datatype.NamespaceDirective
import org.genivi.sota.marshalling.CirceMarshallingSupport
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.PropertyChecks
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.duration._

/**
 * Spec tests for vehicle REST actions
 */
class VehicleResourceSpec extends FunSuite
  with PropertyChecks
  with Matchers
  with ScalatestRouteTest
  with ScalaFutures
  with DatabaseSpec
  with VehicleDatabaseSpec
  with UpdateResourcesDatabaseSpec
  with Namespaces {

  import CirceMarshallingSupport._
  import NamespaceDirective._

  implicit val routeTimeout: RouteTestTimeout =
    RouteTestTimeout(10.second)

  val rviUri = Uri(system.settings.config.getString( "rvi.endpoint" ))
  val serverTransport = HttpTransport( rviUri )
  implicit val rviClient = new JsonRpcRviClient( serverTransport.requestTransport, system.dispatcher)

  val fakeResolver = new FakeExternalResolver()
  val fakeDeviceRegistry = new FakeDeviceRegistry()

  lazy val service = new VehiclesResource(db, rviClient, fakeResolver, fakeDeviceRegistry, defaultNamespaceExtractor)

  val BasePath = Path("/vehicles")

  implicit val patience = PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))

  implicit val _db = db

  def resourceUri( pathSuffix : String ) : Uri = {
    Uri.Empty.withPath(BasePath / pathSuffix)
  }

  def vehicleUri(vin: Vehicle.Vin)  = Uri.Empty.withPath( BasePath / vin.get )

  test("returns vehicle status even if Vin is in device registry but not local db") {
    val f = for {
      (_, vin) <- createDevice(fakeDeviceRegistry)
      (did, vin2) <- createDevice(fakeDeviceRegistry)
      _ <- db.run(createUpdateSpecFor(vin2))
      _ <- fakeDeviceRegistry.updateLastSeen(did, org.joda.time.DateTime.now.minusHours(1))
    } yield (vin, vin2)

    whenReady(f) { case(vin, vin2) =>
      val url = Uri.Empty
        .withPath(BasePath)
        .withQuery(Uri.Query("status" -> "true"))

      Get(url) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        val parsedResponse = responseAs[Seq[VehicleUpdateStatus]]

        parsedResponse should have size 2

        val foundVin = parsedResponse.find(_.vin == vin.vin)

        foundVin.flatMap(_.lastSeen) shouldNot be(defined)
        foundVin.map(_.status) should contain(VehicleStatus.NotSeen)

        val foundVin2 = parsedResponse.find(_.vin == vin2.vin)

        foundVin2.flatMap(_.lastSeen) should be(defined)
        foundVin2.map(_.status) should contain(VehicleStatus.Outdated)
      }
    }
  }

  test("search with status=true returns current status for a vehicle") {
    whenReady(createVehicle(fakeDeviceRegistry)) { _ =>
      val url = Uri.Empty
        .withPath(BasePath)
        .withQuery(Uri.Query("status" -> "true"))

      Get(url) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        val parsedResponse = responseAs[Seq[VehicleUpdateStatus]].headOption

        parsedResponse.flatMap(_.lastSeen) shouldNot be(defined)
        parsedResponse.map(_.status) should contain(VehicleStatus.NotSeen)
      }
    }
  }
}
