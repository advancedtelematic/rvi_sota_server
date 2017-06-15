/*
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 *  License: MPL-2.0
 */
package org.genivi.sota.core.daemon

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import eu.timepit.refined.api.Refined
import org.genivi.sota.DefaultPatience
import org.genivi.sota.core.{DatabaseSpec, FakeDeviceRegistry, UpdateService}
import org.genivi.sota.core.client.FakeReposerverClient
import org.genivi.sota.core.db.{Campaigns, Packages}
import org.genivi.sota.core.resolver.DefaultConnectivity
import org.genivi.sota.core.transfer.DefaultUpdateNotifier
import org.genivi.sota.data.{Namespaces, PackageId, Uuid}
import org.genivi.sota.messaging.Commit.Commit
import org.genivi.sota.messaging.LocalMessageBus
import org.genivi.sota.messaging.Messages.{DeltaGenerationFailed, GeneratedDelta}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FunSuite, ShouldMatchers}
import org.genivi.sota.core.Generators.PackageGen

import scala.concurrent.ExecutionContext

class DeltaListenerSpec extends FunSuite
  with DatabaseSpec
  with ShouldMatchers
  with ScalaFutures
  with DefaultPatience
  with Namespaces
  with BeforeAndAfterAll {

  implicit val ec = ExecutionContext.global
  implicit val system = ActorSystem(this.getClass.getSimpleName)
  implicit val mat = ActorMaterializer()

  implicit val connectivity = DefaultConnectivity
  val deviceRegistry = new FakeDeviceRegistry(Namespaces.defaultNs)
  val updateService = new UpdateService(DefaultUpdateNotifier, deviceRegistry)
  val tufClient = FakeReposerverClient
  val publisher = LocalMessageBus.publisher(system)

  val listener = new DeltaListener(deviceRegistry, updateService, publisher)

  val from: Commit = Refined.unsafeApply("75857a45899985be4c4d941e90b6b396d6c92a4c7437aaf0bf102089fe21379d")
  val to: Commit = Refined.unsafeApply("663ea1bfffe5038f3f0cf667f14c4257eff52d77ce7f2a218f72e9286616ea39")


  test("ignores when message is not valid") {
    val id = db.run(Campaigns.create(defaultNs, "some campaign")).futureValue
    val msg = GeneratedDelta(id.underlying, defaultNs, from, to, 10L)
    listener.generatedDeltaAction(msg).futureValue shouldBe Done
  }

  test("ignores when there is no lauch campaign request") {
    val fromPid = PackageId(Refined.unsafeApply("treehub"), Refined.unsafeApply(from.value))
    val toPid = PackageId(Refined.unsafeApply("treehub"), Refined.unsafeApply(to.value))
    val pkgFrom = PackageGen.sample.get.copy(id = fromPid)
    val pkgTo = PackageGen.sample.get.copy(id = toPid)

    val dbio = for {
      id <- Campaigns.create(defaultNs, "other campaign")
      _ <- Packages.create(pkgFrom)
      _ <- Packages.create(pkgTo)
      _ <- Campaigns.setDeltaFrom(id, Some(fromPid))
      _ <- Campaigns.setPackage(id, toPid)
    } yield id

    val campaignId = db.run(dbio).futureValue

    val msg = GeneratedDelta(campaignId.underlying, defaultNs, from, to, 10L)

    listener.generatedDeltaAction(msg).futureValue shouldBe Done
  }

  test("ignores non existent campaigns when receiving Generated messages") {
    val msg = GeneratedDelta(Uuid.generate(), defaultNs, from, to, 10L)
    listener.generatedDeltaAction(msg).futureValue shouldBe Done
  }

  test("ignores non existent campaigns when receiving failed messages") {
    val msg = DeltaGenerationFailed(Uuid.generate(), defaultNs)

    listener.deltaGenerationFailedAction(msg).futureValue shouldBe Done
  }

}
