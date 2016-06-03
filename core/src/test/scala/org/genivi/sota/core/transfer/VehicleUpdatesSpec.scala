package org.genivi.sota.core.transfer

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.genivi.sota.core.db._
import org.genivi.sota.core.rvi.UpdateReport
import org.genivi.sota.core.rvi.OperationResult
import org.genivi.sota.data.VehicleGenerators
import org.genivi.sota.core._
import org.genivi.sota.core.data.UpdateStatus
import org.scalacheck.Gen
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.genivi.sota.db.SlickExtensions
import org.scalatest.time.{Millis, Seconds, Span}
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.joda.time.DateTime
import slick.driver.MySQLDriver.api._

import scala.concurrent.ExecutionContext

class VehicleUpdatesSpec extends FunSuite
  with ShouldMatchers
  with BeforeAndAfterAll
  with Inspectors
  with ScalaFutures
  with DatabaseSpec
  with UpdateResourcesDatabaseSpec {

  import Generators._
  import SlickExtensions._
  import VehicleUpdates._
  import UpdateStatus._

  implicit val actorSystem = ActorSystem("InstalledPackagesUpdateSpec-ActorSystem")
  implicit val materializer = ActorMaterializer()

  implicit val ec = ExecutionContext.global
  implicit val _db = db
  implicit val patience = PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))

  test("forwards request to resolver client") {
    val resolverClient = new FakeExternalResolver
    val vin = VehicleGenerators.genVin.sample.get
    val packageIds = Gen.listOf(PackageIdGen).sample.get
    val f = update(vin, packageIds, resolverClient)

    whenReady(f) { _ =>
      forAll(packageIds) { id =>
        resolverClient.installedPackages should contain(id)
      }
    }
  }

  test("marks reported packages as installed") {
    val f = for {
      (_, vehicle, updateSpec) <- createUpdateSpec()
      result = OperationResult("opid", 1, "some result")
      report = UpdateReport(updateSpec.request.id, List(result))
      _ <- reportInstall(vehicle.vin, report)
      updatedSpec <- db.run(findUpdateSpecFor(vehicle.vin, updateSpec.request.id))
      history <- db.run(InstallHistories.list(vehicle.namespace, vehicle.vin))
    } yield (updatedSpec.status, history)

    whenReady(f) { case (newStatus, history) =>
      newStatus should be(UpdateStatus.Finished)
      history.map(_.success) should contain(true)
    }
  }

  test("when multiple packages are pending sorted by installPos") {
    val dbIO = for {
      (_, vehicle, updateSpec0) <- createUpdateSpecAction()
      (_, updateSpec1) <- createUpdateSpecFor(vehicle, installPos = 2)
      result <- findPendingPackageIdsFor(vehicle.vin)
    } yield (result, updateSpec0, updateSpec1)

    whenReady(db.run(dbIO)) { case (result, updateSpec0, updateSpec1)  =>
      result shouldNot be(empty)
      result should have(size(2))

      result match {
        case Seq(first, second) =>
          first.id shouldBe updateSpec0.request.id
          second.id shouldBe updateSpec1.request.id
        case _ =>
          fail("returned package list does not have expected elements")
      }
    }
  }

  test("sets install priority for one package") {
    val dbIO = for {
      (pck, vehicle, spec0) <- createUpdateSpecAction()
      (_, spec1) <- createUpdateSpecFor(vehicle)
      _ <- persistInstallOrder(vehicle.vin, List(spec0.request.id, spec1.request.id))
      dbSpecs <- findPendingPackageIdsFor(vehicle.vin)
    } yield (dbSpecs, spec0, spec1)

    whenReady(db.run(dbIO)) { case (Seq(dbSpec0, dbSpec1), spec0, spec1) =>
      dbSpec0.id shouldBe spec0.request.id
      dbSpec0.installPos shouldBe 0

      dbSpec1.id shouldBe spec1.request.id
      dbSpec1.installPos shouldBe 1
    }
  }

  test("can only sort pending update requests") {
    import UpdateSpecs._
    import org.genivi.sota.refined.SlickRefined._

    val dbIO = for {
      (pck, vehicle, spec0) <- createUpdateSpecAction()
      _ <- updateSpecs.filter(_.vin === vehicle.vin).map(_.status).update(UpdateStatus.InFlight)
      (_, spec1) <- createUpdateSpecFor(vehicle)
      result <- persistInstallOrder(vehicle.vin, List(spec0.request.id, spec1.request.id))
    } yield result

    val f = db.run(dbIO)

    whenReady(f.failed) { t =>
      t shouldBe a[SetOrderFailed]
      t.getMessage should include("need to be pending")
    }
  }

  test("fails when not specifying all update request in order") {
    val dbIO = for {
      (pck, v, spec0) <- createUpdateSpecAction()
      (_, spec1) <- createUpdateSpecFor(v)
      result <- persistInstallOrder(v.vin, List(spec1.request.id))
    } yield result

    val f = db.run(dbIO)

    whenReady(f.failed) { t =>
      t shouldBe a[SetOrderFailed]
      t.getMessage should include("need to be specified")
    }
  }
}
