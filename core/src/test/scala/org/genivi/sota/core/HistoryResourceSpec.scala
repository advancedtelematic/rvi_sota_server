/*
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */

package org.genivi.sota.core

import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.Unmarshaller._
import cats.syntax.show._
import io.circe.generic.auto._
import org.genivi.sota.core.data.ClientInstallHistory
import org.genivi.sota.core.transfer.DeviceUpdates
import org.genivi.sota.data.DeviceGenerators.{genDeviceId, genDeviceT}
import org.genivi.sota.data.{Namespaces, Uuid}
import org.genivi.sota.DefaultPatience
import org.genivi.sota.http.NamespaceDirectives.defaultNamespaceExtractor
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.messaging.MessageBusPublisher
import org.genivi.sota.core.rvi.{OperationResult, UpdateReport}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FunSuite, ShouldMatchers}
import scala.concurrent.Future

class HistoryResourceSpec() extends FunSuite
  with ScalatestRouteTest
  with DatabaseSpec
  with UpdateResourcesDatabaseSpec
  with ShouldMatchers
  with ScalaFutures
  with LongRequestTimeout
  with DefaultPatience
  with Generators {

  val deviceRegistry = new FakeDeviceRegistry(Namespaces.defaultNs)
  val service = new HistoryResource(deviceRegistry, defaultNamespaceExtractor)
  val baseUri = Uri.Empty.withPath(Path("/history"))

  test("history") {
    val device = genDeviceT.sample.get.copy(deviceId = Some(genDeviceId.sample.get))

    whenReady(deviceRegistry.createDevice(device)) { case uuid =>
      val uri = Uri.Empty.withPath(baseUri.path).withQuery(Query("uuid" -> uuid.show))

      Get(baseUri.withQuery(Query("uuid" -> uuid.show))) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[Seq[ClientInstallHistory]] should be(empty)
      }
    }
  }

  test("after reportInstall entry shows up in history") {
    val id = Uuid.generate
    val act = for {
      (_, dev, us1) <- createUpdateSpec()
      (_, us2) <- db.run(createUpdateSpecFor(dev.uuid))
      _ <- Future.successful(deviceRegistry.addDevice(dev))
      result1 = OperationResult(id, 1, "some string")
      report1 = UpdateReport(us1.request.id, List(result1))
      _ <- DeviceUpdates.reportInstall(dev.uuid, report1, MessageBusPublisher.ignore)
      result2 = OperationResult(id, 1, "some string")
      report2 = UpdateReport(us2.request.id, List(result2))
      _ <- DeviceUpdates.reportInstall(dev.uuid, report2, MessageBusPublisher.ignore)
    } yield dev.uuid

    whenReady(act) { uuid =>
      Get(baseUri.withQuery(Query("uuid" -> uuid.show))) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[Seq[ClientInstallHistory]].length shouldBe 2
      }
    }
  }

  test("[PRO-2430] history doesn't show duplicates") {
    val id = Uuid.generate
    val act = for {
      (pkg, dev, us) <- createUpdateSpec()
      _ <- Future.successful(deviceRegistry.addDevice(dev))
      result1 = OperationResult(id, 1, "some string")
      report1 = UpdateReport(us.request.id, List(result1))
      _ <- DeviceUpdates.reportInstall(dev.uuid, report1, MessageBusPublisher.ignore)
      result2 = OperationResult(id, 0, "some string")
      report2 = UpdateReport(us.request.id, List(result2))
      _ <- DeviceUpdates.reportInstall(dev.uuid, report2, MessageBusPublisher.ignore)
    } yield dev.uuid

    whenReady(act) { uuid =>
      Get(baseUri.withQuery(Query("uuid" -> uuid.show))) ~> service.route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[Seq[ClientInstallHistory]].length shouldBe 1
      }
    }
  }
}
