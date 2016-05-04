package org.genivi.sota.core.transfer

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.genivi.sota.core.data.UpdateStatus
import org.genivi.sota.core.db._
import org.genivi.sota.core.rvi.OperationResult
import org.genivi.sota.core.rvi.UpdateReport
import org.genivi.sota.core.{DatabaseSpec, FakeExternalResolver, Generators, UpdateResourcesDatabaseSpec}
import org.genivi.sota.data.DeviceGenerators
import org.genivi.sota.db.SlickExtensions
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.joda.time.DateTime
import org.scalacheck.Gen
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import scala.concurrent.ExecutionContext


class InstalledPackagesUpdateSpec extends FunSuite
  with ShouldMatchers
  with BeforeAndAfterAll
  with Inspectors
  with ScalaFutures
  with DatabaseSpec
  with UpdateResourcesDatabaseSpec {

  import Generators._
  import SlickExtensions._
  import InstalledPackagesUpdate._

  implicit val actorSystem = ActorSystem("InstalledPackagesUpdateSpec-ActorSystem")
  implicit val materializer = ActorMaterializer()

  implicit val ec = ExecutionContext.global
  implicit val _db = db
  implicit val patience = PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))

  test("forwards request to resolver client") {
    val resolverClient = new FakeExternalResolver
    val device = DeviceGenerators.genDevice.sample.get
    val packageIds = Gen.listOf(PackageIdGen).sample.get
    val f = update(device.uuid, packageIds, resolverClient)

    whenReady(f) { _ =>
      forAll(packageIds) { id =>
        resolverClient.installedPackages should contain(id)
      }
    }
  }

  test("marks reported packages as installed") {
    val f = for {
      (_, device, updateSpec) <- createUpdateSpec()
      result = OperationResult("opid", 1, "some result")
      report = UpdateReport(updateSpec.request.id, List(result))
      _ <- reportInstall(device.uuid, report)
      updatedSpec <- db.run(findUpdateSpecFor(device.uuid, updateSpec.request.id))
      history <- db.run(InstallHistories.list(device.namespace, device.uuid))
    } yield (updatedSpec.status, history)

    whenReady(f) { case (newStatus, history) =>
      newStatus should be(UpdateStatus.Finished)
      history.map(_.success) should contain(true)
    }
  }

  test("when multiple packages are pending, return all of them, sorted by creation date") {
    val secondCreationTime = DateTime.now.plusHours(1)

    val f = for {
      (_, device, updateRequest0) <- createUpdateSpec()
      (_, updateRequest1) <- db.run(createUpdateSpecFor(device, secondCreationTime))
      result <- db.run(findPendingPackageIdsFor(defaultNamespace, device.uuid))
    } yield (result, updateRequest0, updateRequest1)

    whenReady(f) { case (result, updateRequest0, updateRequest1)  =>
      result shouldNot be(empty)
      result should have(size(2))

      result match {
        case Seq(first, second) =>
          first.id shouldBe updateRequest0.request.id
          second.id shouldBe updateRequest1.request.id
        case _ =>
          fail("returned package list does not have expected elements")
      }
    }
  }
}
