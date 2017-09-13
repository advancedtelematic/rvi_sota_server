/*
 * Copyright: Copyright (C) 2017, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.core.daemon

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import java.security.MessageDigest
import java.util.UUID

import org.genivi.sota.DefaultPatience
import org.genivi.sota.core._
import org.genivi.sota.core.client.FakeReposerverClient
import org.genivi.sota.core.resolver.DefaultConnectivity
import org.genivi.sota.core.transfer.DefaultUpdateNotifier
import org.genivi.sota.data._
import org.genivi.sota.messaging.LocalMessageBus
import org.genivi.sota.messaging.Messages.TreehubCommit
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FunSuite, ShouldMatchers}

import scala.concurrent.{ExecutionContext, Future}
import com.advancedtelematic.libtuf.data.TufDataType.RepoId

class TreehubCommitListenerSpec extends FunSuite
  with DatabaseSpec
  with ShouldMatchers
  with ScalaFutures
  with DefaultPatience
  with Namespaces
  with BeforeAndAfterAll {

  implicit val ec = ExecutionContext.global
  implicit val system = ActorSystem("TreehubCommitListenerSpec")
  implicit val mat = ActorMaterializer()

  def toSHA256(s: String) =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes()).map { "%02x".format(_) }.foldLeft("") {_ + _}

  implicit val connectivity = DefaultConnectivity
  val deviceRegistry = new FakeDeviceRegistry(Namespaces.defaultNs)
  val updateService = new UpdateService(DefaultUpdateNotifier, deviceRegistry)
  val tufClient = FakeReposerverClient
  val publisher = LocalMessageBus.publisher(system)
  val listener = new TreehubCommitListener(db, tufClient, publisher)

  def runListener(): Future[(RepoId, TreehubCommit)] = {
    val commit = toSHA256(UUID.randomUUID().toString)

    val event = TreehubCommit(ns = defaultNs, commit = commit, refName = "some_ref_name", description = commit,
      size = 1234, uri = "some_uri")

    for {
      repoId   <- tufClient.createRoot(com.advancedtelematic.libats.data.Namespace(defaultNs.get))
      _   <- listener.action(event)
    } yield (repoId, event)
  }

  test("treehub commit event adds tuf target") {
    val (repoId, event) = runListener().futureValue

    val storedTarget = tufClient.targets(repoId).head

    storedTarget.namespace.get shouldBe defaultNs.get
    storedTarget.name.map(_.value) should contain(event.refName)
    storedTarget.version.map(_.value) should contain(event.commit)
    storedTarget.hardwareIds.map(_.value) should contain(event.refName)
  }

  override def afterAll(): Unit = {
    super.afterAll()
    system.terminate()
  }

}
