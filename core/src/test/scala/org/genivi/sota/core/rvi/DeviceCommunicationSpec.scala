/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.rvi

import akka.http.scaladsl.model.HttpResponse
import com.github.nscala_time.time.Imports._
import eu.timepit.refined.Refined
import java.net.URL
import java.util.concurrent.TimeUnit
import org.genivi.sota.core._
import org.genivi.sota.core.data.{InstallCampaign, InstallRequest, Package, Vehicle, PackageId}
import org.genivi.sota.core.db._
import org.scalatest._
import org.scalatest.prop.PropertyChecks
import slick.driver.MySQLDriver.api._

import scala.concurrent.{Await, Future}

class RunCampaignsSpec extends PropSpec
    with Matchers
    with PropertyChecks
    with BeforeAndAfterEach with Generators {

  import org.genivi.sota.core.Generators._
  import org.scalacheck._

  import scala.concurrent.ExecutionContext.Implicits.global

  val databaseName = "test-database"
  val db = Database.forConfig(databaseName)

  override def beforeEach {
    TestDatabase.resetDatabase(databaseName)
  }

  type Interval = (DateTime, DateTime)

  import InstallRequest.Status

  def campaign(pkg: Package, priority: Int, startAfter: DateTime, endBefore: DateTime): InstallCampaign =
    InstallCampaign(None, pkg.id, priority, startAfter, endBefore)

  def request(pkg: Package, campaignId: Long, vin: Vehicle, status: InstallRequest.Status = Status.NotProcessed): InstallRequest =
    InstallRequest(None, campaignId, pkg.id, vin.vin, status, None)

  case class State(pkg1: Package,
                   pkg2: Package,
                   vin1: Vehicle,
                   vin2: Vehicle,
                   vin3: Vehicle,
                   highPriority: Int,
                   lowPriority: Int,
                   currentInterval: Interval,
                   nonCurrentInterval: Interval) {
    override def toString(): String = Seq(
      "pkg1" -> pkg1,
      "pkg2" -> pkg2,
      "vin1" -> vin1,
      "vin2" -> vin2,
      "vin3" -> vin3,
      "highPriority" -> highPriority,
      "lowPriority" -> lowPriority,
      "currentInterval" -> currentInterval,
      "nonCurrentInterval" -> nonCurrentInterval
    ).map { case (n, p) => s"$n: $p\n" }.mkString
  }

  val states: Gen[State] = for {
    pkg1 <- PackageGen
    pkg2 <- PackageGen
    vin1 <- vinGen
    vin2 <- vinGen
    vin3 <- vinGen
    highPriority <- Gen.choose(50, 200)
    lowPriority <- Gen.choose(1, 49)

    now = DateTime.now

    i <- Gen.choose(5, 200)
    currentInterval = (now - i.days, now + i.days)
    nonCurrentInterval = (now - (i * 2).days, now - i.days)
  } yield State(pkg1, pkg2, vin1, vin2, vin3, highPriority, lowPriority, currentInterval, nonCurrentInterval)

  def persisted(p: Package): DBIO[Package] = Packages.create(p)
  def persisted(v: Vehicle): DBIO[Vehicle.IdentificationNumber] = Vehicles.create(v).map { _ => v.vin }
  def persisted(r: InstallRequest): DBIO[Long] = InstallRequests.create(r).map(_.id.head)
  def persisted(c: InstallCampaign): DBIO[Long] = InstallCampaigns.create(c).map(_.id.head)
  def persisted(s: State): DBIO[(Package, Package, Long, Long, Long, Long, Long)] = for {
    _ <- persisted(s.pkg1)
    _ <- persisted(s.pkg2)

    _ <- persisted(s.vin1)
    _ <- persisted(s.vin2)
    _ <- persisted(s.vin3)

    highPriorityCurrentCampaignId <- persisted(campaign(s.pkg1, s.highPriority, s.currentInterval._1, s.currentInterval._2))
    r1 <- persisted(request(s.pkg1, highPriorityCurrentCampaignId, s.vin1))
    r2 <- persisted(request(s.pkg1, highPriorityCurrentCampaignId, s.vin2, Status.Notified))

    lowPriorityCurrentCampaignId <- persisted(campaign(s.pkg2, s.lowPriority, s.currentInterval._1, s.currentInterval._2))
    r3 <- persisted(request(s.pkg2, lowPriorityCurrentCampaignId, s.vin2))
    r4 <- persisted(request(s.pkg2, lowPriorityCurrentCampaignId, s.vin1, Status.Notified))

    nonCurrentCampaignId <- persisted(campaign(s.pkg1, s.highPriority, s.nonCurrentInterval._1, s.nonCurrentInterval._2))
    r5 <- persisted(request(s.pkg1, nonCurrentCampaignId, s.vin3))
  } yield (s.pkg1, s.pkg2, r1, r2, r3, r4, r5)


  class RviMock(worksWhen: ((Vehicle.IdentificationNumber, Package)) => Boolean = Function.const(true)) extends RviInterface {
    val msgs = scala.collection.mutable.Set[(Vehicle.IdentificationNumber, Package)]()
    def registerService(networkAddress: URL, service: String): Future[HttpResponse] = ???
    def notify(s: Vehicle.IdentificationNumber, p: Package): Future[HttpResponse] =
      if (worksWhen((s, p))) {
        this.synchronized(msgs += ((s,p)))
        Future.successful(HttpResponse())
      } else Future.failed[HttpResponse](new RviInterface.UnexpectedRviResponse(HttpResponse()))
    def transferStart(transactionId: Long, destination: String, packageIdentifier: String, byteSize: Long, chunkSize: Long, checksum: String): Future[HttpResponse] = ???
    def transferChunk(transactionId: Long, destination: String, index: Long, data: Array[Byte]): Future[HttpResponse] = ???
    def transferFinish(transactionId: Long, destination: String): Future[HttpResponse] = ???
  }

  property("a DeviceCommunication runs the current campaigns") {
    forAll(states) { state =>
      val rviNode = new RviMock
      val devComm = new DeviceCommunication(db, rviNode, _ => ???, e => println(e) )

      val test = for {
        (pkg1, pkg2, rid1, _, rid3, _, rid5) <- db.run(persisted(state))
        expectedMessages = Seq((state.vin1.vin, pkg1),
                               (state.vin2.vin, pkg2))
        _ <- devComm.runCurrentCampaigns()

        _ = expectedMessages.sortBy(_._1).zip(rviNode.msgs.toSeq.sortBy(_._1)).foreach { case (expected, actual) =>
          expected should equal (actual)
        }

        _ <- db.run(InstallRequests.list(Seq(rid1, rid3, rid5))).map { case Seq(r1, r3, r5) =>
          r1.statusCode should equal(Status.Notified)
          r3.statusCode should equal(Status.Notified)
          r5.statusCode should equal(Status.NotProcessed)
        }
      } yield ()

      test.onFailure { case e => fail(e) }

      import scala.concurrent.duration._
      Await.result(test, Duration(10, TimeUnit.SECONDS))
    }
  }

  property("a DeviceCommunication logs all failures but still processes what it can") {
    forAll(states) { state =>
      val rviNode = new RviMock({ case (s, p) => s != state.vin1.vin })
      val errors = scala.collection.mutable.Set[Throwable]()
      val devComm = new DeviceCommunication(db, rviNode, _ => ???, e => { this.synchronized { errors += e } })

      val test = for {
        (pkg1, pkg2, rid1, _, rid3, _, _) <- db.run(persisted(state))
        _ <- devComm.runCurrentCampaigns()

        expectedMessages = Seq((state.vin2.vin, pkg2))
        _ = expectedMessages.length should equal(rviNode.msgs.toSeq.length)
        _ = expectedMessages.sortBy(_._1).zip(rviNode.msgs.toSeq.sortBy(_._1)).foreach {
          case (expected, actual) => expected should equal (actual)
        }
        _ = errors.toSeq.length should equal (1)

        _ <- db.run(InstallRequests.list(Seq(rid1, rid3))).flatMap { case Seq(r1, r3) =>
          r1.statusCode should equal(Status.NotProcessed)
          r3.statusCode should equal(Status.Notified)
          db.run(InstallRequests.updateNotified(Seq(r1))) // for cleanup
        }
      } yield ()

      test.onFailure { case e => fail(e) }

      {
        import scala.concurrent.duration.DurationInt
        Await.result(test, DurationInt(10).seconds)
      }
    }
  }
}
