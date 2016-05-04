package org.genivi.sota.core

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.Unmarshaller._
import io.circe.generic.auto._
import io.circe.{Encoder, Json}
import java.util.UUID
import org.genivi.sota.core.data.response.PendingUpdateResponse
import org.genivi.sota.core.data.{Package, UpdateRequest, UpdateStatus}
import org.genivi.sota.core.db.{InstallHistories, Packages, Devices}
import org.genivi.sota.core.resolver.{Connectivity, ConnectivityClient, DefaultConnectivity}
import org.genivi.sota.core.rvi.{InstallReport, OperationResult, UpdateReport}
import org.genivi.sota.core.transfer.InstalledPackagesUpdate
import org.genivi.sota.data.PackageIdGenerators._
import org.genivi.sota.data.DeviceGenerators._
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.joda.time.DateTime
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{FunSuite, Inspectors, ShouldMatchers}
import scala.concurrent.Future


class DeviceUpdatesResourceSpec extends FunSuite
  with ShouldMatchers
  with ScalatestRouteTest
  with ScalaFutures
  with DatabaseSpec
  with Inspectors
  with UpdateResourcesDatabaseSpec
  with DeviceDatabaseSpec {

  val fakeResolver = new FakeExternalResolver()

  implicit val connectivity = new FakeConnectivity()

  lazy val service = new DeviceUpdatesResource(db, fakeResolver)

  val device = genDevice.sample.get

  val baseUri = Uri.Empty.withPath(Path("/api/v1/device_updates"))

  val deviceUri = baseUri.withPath(baseUri.path / device.uuid.toString)

  implicit val patience = PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))

  implicit val _db = db

  test("install updates are forwarded to external resolver") {
    val fakeResolverClient = new FakeExternalResolver()
    val devicesResource = new DeviceUpdatesResource(db, fakeResolverClient)
    val packageIds = Gen.listOf(genPackageId).sample.get

    Put(deviceUri, packageIds) ~> devicesResource.route ~> check {
      status shouldBe StatusCodes.NoContent

      packageIds.foreach { p =>
        fakeResolverClient.installedPackages.toList should contain(p)
      }
    }
  }

  test("GET to download file returns the file contents") {
    whenReady(createUpdateSpec()) { case (packageModel, device, updateSpec) =>
      val url = baseUri.withPath(baseUri.path / device.uuid.toString / updateSpec.request.id.toString / "download")

      Get(url) ~> service.route ~> check {
        status shouldBe StatusCodes.OK

        responseEntity.contentLengthOption should contain(packageModel.size)
      }
    }
  }

  test("GET returns 404 if there is no package with the given id") {
    val uuid = Gen.uuid.sample.get
    val url = deviceUri.withPath(deviceUri.path / uuid.toString / "download")

    Get(url) ~> service.route ~> check {
      status shouldBe StatusCodes.NotFound
      responseAs[String] should include("Package not found")
    }
  }

  test("GET update requests for a device returns a list of PendingUpdateResponse") {
    whenReady(createUpdateSpec()) { case (_, device, updateSpec) =>
      val uri = baseUri.withPath(baseUri.path / device.uuid.toString)

      Get(uri) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        val parsedResponse = responseAs[List[PendingUpdateResponse]]
        parsedResponse shouldNot be(empty)
        parsedResponse.map(_.requestId) should be(List(updateSpec.request.id))
        parsedResponse.map(_.packageId) should be(List(updateSpec.request.packageId))
      }
    }
  }

  test("sets device last seen when device asks for updates") {
    whenReady(createDevice()) { device =>
      val uri = baseUri.withPath(baseUri.path / device.uuid.toString)

      val now = DateTime.now.minusSeconds(10)

      Get(uri) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[List[UUID]] should be(empty)

        val deviceF = db.run(Devices.list().map(_.find(_.uuid == device.uuid)))

        whenReady(deviceF) {
          case Some(d) =>
            d.lastSeen shouldBe defined
            d.lastSeen.get.isAfter(now) shouldBe true
          case _ =>
            fail("Device should be in database")
        }
      }
    }
  }

  test("POST an update report updates an UpdateSpec status") {
    whenReady(createUpdateSpec()) { case (_, device, updateSpec) =>
      val url = baseUri.withPath(baseUri.path / device.uuid.toString / updateSpec.request.id.toString)
      val result = OperationResult("opid", 1, "some result")
      val updateReport = UpdateReport(updateSpec.request.id, List(result))
      val installReport = InstallReport(device.uuid, updateReport)

      Post(url, installReport) ~> service.route ~> check {
        status shouldBe StatusCodes.NoContent

        val dbIO = for {
          updateSpec <- InstalledPackagesUpdate.findUpdateSpecFor(device.uuid, updateSpec.request.id)
          histories <- InstallHistories.list(device.namespace, device.uuid)
        } yield (updateSpec, histories.last)

        whenReady(db.run(dbIO)) { case (updatedSpec, lastHistory) =>
          updatedSpec.status shouldBe UpdateStatus.Finished
          lastHistory.success shouldBe true
        }
      }
    }
  }

  test("Returns 404 if package does not exist") {
    val f = db.run(Devices.create(device))

    whenReady(f) { device =>
      val fakeUpdateRequestUuid = UUID.randomUUID()
      val url = baseUri.withPath(baseUri.path / device.uuid.toString / fakeUpdateRequestUuid.toString)
      val result = OperationResult(UUID.randomUUID().toString, 1, "some result")
      val updateReport = UpdateReport(fakeUpdateRequestUuid, List(result))
      val installReport = InstallReport(device.uuid, updateReport)

      Post(url, installReport) ~> service.route ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[String] should include("Could not find an update request with id ")
      }
    }
  }

  test("GET to download a file returns 3xx if the package URL is an s3 URI") {
    val service = new DeviceUpdatesResource(db, fakeResolver) {
      override lazy val packageRetrievalOp: (Package) => Future[HttpResponse] = {
        _ => Future.successful {
          HttpResponse(StatusCodes.Found, Location("https://some-fake-place") :: Nil)
        }
      }
    }

    val f = for {
      (packageModel, device, updateSpec) <- createUpdateSpec()
      _ <- db.run(Packages.create(packageModel.copy(uri = "https://amazonaws.com/file.rpm")))
    } yield (packageModel, device, updateSpec)

    whenReady(f) { case (packageModel, device, updateSpec) =>
      val url = baseUri.withPath(baseUri.path / device.uuid.toString / updateSpec.request.id.toString / "download")
      Get(url) ~> service.route ~> check {
        status shouldBe StatusCodes.Found
        header("Location").map(_.value()) should contain("https://some-fake-place")
      }
    }
  }


  test("POST on queues a package for update to a specific device") {
    val f = createUpdateSpec()

    whenReady(f) { case (packageModel, device, updateSpec) =>
      val now = DateTime.now
      val url = baseUri.withPath(baseUri.path / device.uuid.toString)

      Post(url, packageModel.id) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        val updateRequest = responseAs[UpdateRequest]
        updateRequest.packageId should be (packageModel.id)
        updateRequest.creationTime.isAfter(now) shouldBe true
      }
    }
  }

  test("POST on /:vin/sync results in an rvi sync") {
    val url = deviceUri.withPath(deviceUri.path / "sync")
    Post(url) ~> service.route ~> check {
      status shouldBe StatusCodes.NoContent

      val service = s"genivi.org/vin/${device.uuid.toString}/sota/getpackages"

      connectivity.sentMessages should contain(service -> Json.Null)
    }
  }
}

class FakeConnectivity extends Connectivity {

  val sentMessages = scala.collection.mutable.Queue.empty[(String, Json)]

  override implicit val transport = { (_: Json) =>
    Future.successful(Json.Null)
  }

  override implicit val client = new ConnectivityClient {
    override def sendMessage[A](service: String, message: A, expirationDate: DateTime)(implicit encoder: Encoder[A]): Future[Int] = {
      val v = (service, encoder(message))
      sentMessages += v

      Future.successful(0)
    }
  }
}
