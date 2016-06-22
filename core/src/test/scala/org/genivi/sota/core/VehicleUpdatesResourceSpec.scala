package org.genivi.sota.core

import akka.http.scaladsl.unmarshalling.Unmarshaller._
import java.util.UUID

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import io.circe.{Encoder, Json}
import org.genivi.sota.core.rvi.{InstallReport, OperationResult, UpdateReport}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FunSuite, Inspectors, ShouldMatchers}
import org.genivi.sota.data.VehicleGenerators._
import org.genivi.sota.data.PackageIdGenerators._
import org.scalacheck.Gen
import org.scalatest.time.{Millis, Seconds, Span}
import org.genivi.sota.core.data.{Package, UpdateRequest, UpdateStatus}
import org.genivi.sota.core.db.{InstallHistories, Packages, Vehicles}
import org.genivi.sota.core.transfer.VehicleUpdates
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import io.circe.generic.auto._
import org.genivi.sota.core.data.client.PendingUpdateRequest
import org.genivi.sota.core.resolver.{Connectivity, ConnectivityClient, DefaultConnectivity}
import org.genivi.sota.datatype.NamespaceDirective
import java.time.Instant

import scala.concurrent.Future

class VehicleUpdatesResourceSpec extends FunSuite
  with ShouldMatchers
  with ScalatestRouteTest
  with ScalaFutures
  with DatabaseSpec
  with Inspectors
  with UpdateResourcesDatabaseSpec
  with VehicleDatabaseSpec {

  import NamespaceDirective._

  val fakeResolver = new FakeExternalResolver()

  implicit val connectivity = new FakeConnectivity()

  lazy val service = new VehicleUpdatesResource(db, fakeResolver, defaultNamespaceExtractor)

  val vehicle = genVehicle.sample.get

  val baseUri = Uri.Empty.withPath(Path("/api/v1/vehicle_updates"))

  val vehicleUri = baseUri.withPath(baseUri.path / vehicle.vin.get)

  implicit val patience = PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))

  implicit val _db = db

  test("install updates are forwarded to external resolver") {
    val fakeResolverClient = new FakeExternalResolver()
    val vehiclesResource = new VehicleUpdatesResource(db, fakeResolverClient, defaultNamespaceExtractor)
    val packageIds = Gen.listOf(genPackageId).sample.get
    val uri = vehicleUri.withPath(vehicleUri.path / "installed")

    Put(uri, packageIds) ~> vehiclesResource.route ~> check {
      status shouldBe StatusCodes.NoContent

      packageIds.foreach { p =>
        fakeResolverClient.installedPackages.toList should contain(p)
      }
    }
  }

  test("GET to download file returns the file contents") {
    whenReady(createUpdateSpec()) { case (packageModel, vehicle, updateSpec) =>
      val url = baseUri.withPath(baseUri.path / vehicle.vin.get / updateSpec.request.id.toString / "download")

      Get(url) ~> service.route ~> check {
        status shouldBe StatusCodes.OK

        responseEntity.contentLengthOption should contain(packageModel.size)
      }
    }
  }

  test("GET returns 404 if there is no package with the given id") {
    val uuid = UUID.randomUUID()
    val url = vehicleUri.withPath(vehicleUri.path / uuid.toString / "download")

    Get(url) ~> service.route ~> check {
      status shouldBe StatusCodes.NotFound
      responseAs[String] should include("Package not found")
    }
  }

  test("GET update requests for a vehicle returns a list of PendingUpdateResponse") {
    whenReady(createUpdateSpec()) { case (_, vehicle, updateSpec) =>
      val uri = baseUri.withPath(baseUri.path / vehicle.vin.get)

      Get(uri) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        val parsedResponse = responseAs[List[PendingUpdateRequest]]
        parsedResponse shouldNot be(empty)
        parsedResponse.map(_.requestId) should be(List(updateSpec.request.id))
        parsedResponse.map(_.packageId) should be(List(updateSpec.request.packageId))
      }
    }
  }

  test("sets vehicle last seen when vehicle asks for updates") {
    whenReady(createVehicle()) { vehicle =>
      val uri = baseUri.withPath(baseUri.path / vehicle.vin.get)

      val now = Instant.now.minusSeconds(10)

      Get(uri) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[List[UUID]] should be(empty)

        val vehicleF = db.run(Vehicles.list().map(_.find(_.vin == vehicle.vin)))

        whenReady(vehicleF) {
          case Some(v) =>
            v.lastSeen shouldBe defined
            v.lastSeen.get.isAfter(now) shouldBe true
          case _ =>
            fail("Vehicle should be in database")
        }
      }
    }
  }

  test("POST an update report updates an UpdateSpec status") {
    whenReady(createUpdateSpec()) { case (_, vehicle, updateSpec) =>
      val url = baseUri.withPath(baseUri.path / vehicle.vin.get / updateSpec.request.id.toString)
      val result = OperationResult("opid", 1, "some result")
      val updateReport = UpdateReport(updateSpec.request.id, List(result))
      val installReport = InstallReport(vehicle.vin, updateReport)

      Post(url, installReport) ~> service.route ~> check {
        status shouldBe StatusCodes.NoContent

        val dbIO = for {
          updateSpec <- VehicleUpdates.findUpdateSpecFor(vehicle.vin, updateSpec.request.id)
          histories <- InstallHistories.list(vehicle.namespace, vehicle.vin)
        } yield (updateSpec, histories.last)

        whenReady(db.run(dbIO)) { case (updatedSpec, lastHistory) =>
          updatedSpec.status shouldBe UpdateStatus.Finished
          lastHistory.success shouldBe true
        }
      }
    }
  }

  test("Returns 404 if package does not exist") {
    val f = db.run(Vehicles.create(vehicle))

    whenReady(f) { vehicle =>
      val fakeUpdateRequestUuid = UUID.randomUUID()
      val url = baseUri.withPath(baseUri.path / vehicle.vin.get / fakeUpdateRequestUuid.toString)
      val result = OperationResult(UUID.randomUUID().toString, 1, "some result")
      val updateReport = UpdateReport(fakeUpdateRequestUuid, List(result))
      val installReport = InstallReport(vehicle.vin, updateReport)

      Post(url, installReport) ~> service.route ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[String] should include("Could not find an update request with id ")
      }
    }
  }

  test("GET to download a file returns 3xx if the package URL is an s3 URI") {
    val service = new VehicleUpdatesResource(db, fakeResolver, defaultNamespaceExtractor) {
      override lazy val packageRetrievalOp: (Package) => Future[HttpResponse] = {
        _ => Future.successful {
          HttpResponse(StatusCodes.Found, Location("https://some-fake-place") :: Nil)
        }
      }
    }

    val f = for {
      (packageModel, vehicle, updateSpec) <- createUpdateSpec()
      _ <- db.run(Packages.create(packageModel.copy(uri = "https://amazonaws.com/file.rpm")))
    } yield (packageModel, vehicle, updateSpec)

    whenReady(f) { case (packageModel, vehicle, updateSpec) =>
      val url = baseUri.withPath(baseUri.path / vehicle.vin.get / updateSpec.request.id.toString / "download")
      Get(url) ~> service.route ~> check {
        status shouldBe StatusCodes.Found
        header("Location").map(_.value()) should contain("https://some-fake-place")
      }
    }
  }


  test("POST on queues a package for update to a specific vehile") {
    val f = createUpdateSpec()

    whenReady(f) { case (packageModel, vehicle, updateSpec) =>
      val now = Instant.now
      val url = baseUri.withPath(baseUri.path / vehicle.vin.get)

      Post(url, packageModel.id) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        val updateRequest = responseAs[UpdateRequest]
        updateRequest.packageId should be (packageModel.id)
        updateRequest.creationTime.isAfter(now) shouldBe true
      }
    }
  }

  test("POST on /:vin/sync results in an rvi sync") {
    val url = vehicleUri.withPath(vehicleUri.path / "sync")
    Post(url) ~> service.route ~> check {
      status shouldBe StatusCodes.NoContent

      val service = s"genivi.org/vin/${vehicle.vin.get}/sota/getpackages"

      connectivity.sentMessages should contain(service -> Json.Null)
    }
  }

  test("PUT to set install order should change the package install order") {
    val dbio = for {
      (_, v, spec0) <- createUpdateSpecAction()
      (_, spec1) <- createUpdateSpecFor(v)
    } yield (v, spec0.request.id, spec1.request.id)

    whenReady(db.run(dbio)) { case (v, spec0, spec1) =>
      val url = baseUri.withPath(baseUri.path / v.vin.get / "order")
      val vehicleUrl = baseUri.withPath(baseUri.path / v.vin.get)
      val req = Map(0 -> spec1, 1 -> spec0)

      Put(url, req) ~> service.route ~> check {
        status shouldBe StatusCodes.NoContent

        Get(vehicleUrl) ~> service.route ~> check {
          val pendingUpdates =
            responseAs[List[PendingUpdateRequest]].sortBy(_.installPos).map(_.requestId)

          pendingUpdates shouldBe List(spec1, spec0)
        }
      }
    }
  }

  test("can cancel pending updates") {
    whenReady(createUpdateSpec()) { case (_, vehicle, updateSpec) =>
      val url = baseUri.withPath(baseUri.path / vehicle.vin.get / updateSpec.request.id.toString / "cancelupdate")
      Put(url) ~> service.route ~> check {
        status shouldBe StatusCodes.NoContent

        whenReady(db.run(VehicleUpdates.findUpdateSpecFor(vehicle.vin, updateSpec.request.id))) { case updateSpec =>
          updateSpec.status shouldBe UpdateStatus.Canceled
        }
      }
    }
  }

  test("GET update results for a vehicle returns a list of OperationResults") {
    whenReady(createUpdateSpec()) { case (_, vehicle, updateSpec) =>
      val uri = vehicleUri.withPath(vehicleUri.path / "results")

      Get(uri) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        val parsedResponse = responseAs[List[OperationResult]]
        parsedResponse should be(empty)
      }
    }
  }

  test("GET update results for an update request returns a list of OperationResults") {
    whenReady(createUpdateSpec()) { case (_, vehicle, updateSpec) =>
      val uri = vehicleUri.withPath(vehicleUri.path / updateSpec.request.id.toString / "results")

      Get(uri) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        val parsedResponse = responseAs[List[OperationResult]]
        parsedResponse should be(empty)
      }
    }
  }

  test("after blocking installation queue, no packages are returned even if some are pending for installation") {
    // insert update spec
    whenReady(createUpdateSpec()) { case (packageModel, vehicle, updateSpec) =>
      // block the installation queue of the device
      whenReady(db.run(Vehicles.updateBlockedInstallQueue(vehicle.vin, isBlocked = true))) { case _ =>

        // check zero packages are returned for install
        val url = baseUri.withPath(baseUri.path / vehicle.vin.get / "queued")
        Get(url) ~> service.route ~> check {
          status shouldBe StatusCodes.OK
          val parsedResponse = responseAs[List[PendingUpdateRequest]]
          parsedResponse should be(empty)
        }

        // unblock the installation queue
        val urlUnblock = baseUri.withPath(baseUri.path / vehicle.vin.get / "unblock")
        Put(urlUnblock) ~> service.route ~> check {
          status shouldBe StatusCodes.NoContent
        }

        // check the pending package is returned for install
        Get(url) ~> service.route ~> check {
          status shouldBe StatusCodes.OK
          val parsedResponse = responseAs[List[PendingUpdateRequest]]
          parsedResponse.size shouldBe 1
          val pendingReq = parsedResponse.head
          (updateSpec.request.packageId) shouldBe (pendingReq.packageId)
        }

      }
    }
  }

}

class FakeConnectivity extends Connectivity {

  val sentMessages = scala.collection.mutable.Queue.empty[(String, Json)]

  override implicit val transport = { (_: Json) =>
    Future.successful(Json.Null)
  }

  override implicit val client = new ConnectivityClient {
    override def sendMessage[A](service: String, message: A, expirationDate: Instant)
                               (implicit encoder: Encoder[A]): Future[Int] = {
      val v = (service, encoder(message))
      sentMessages += v

      Future.successful(0)
    }
  }
}
