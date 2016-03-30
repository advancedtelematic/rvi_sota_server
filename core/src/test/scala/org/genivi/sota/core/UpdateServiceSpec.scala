package org.genivi.sota.core


import akka.http.scaladsl.util.FastFuture
import akka.testkit.TestKit
import eu.timepit.refined.api.Refined
import org.genivi.sota.core.data.Package
import org.genivi.sota.core.db.{Packages, UpdateSpecs, Vehicles}
import org.genivi.sota.core.transfer.DefaultUpdateNotifier
import org.genivi.sota.data.{PackageId, Vehicle, VehicleGenerators}
import org.scalacheck.Gen
import org.scalatest.prop.PropertyChecks
import org.scalatest.time.{Millis, Second, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, PropSpec}

import scala.concurrent.{Await, Future}
import scala.util.Random
import slick.jdbc.JdbcBackend._

/**
 * Spec tests for Update service
 */
class UpdateServiceSpec extends PropSpec
  with PropertyChecks
  with Matchers
  with DatabaseSpec
  with BeforeAndAfterAll {

  val packages = PackagesReader.read().take(1000)

  implicit val _db = db

  val system = akka.actor.ActorSystem("UpdateServiseSpec")
  import system.dispatcher

  override def beforeAll() : Unit = {
    super.beforeAll()
    import scala.concurrent.duration.DurationInt
    Await.ready( Future.sequence( packages.map( p => db.run( Packages.create(p) ) )), 50.seconds)
  }

  import Generators._

  implicit val updateQueueLog = akka.event.Logging(system, "sota.core.updateQueue")

  import org.genivi.sota.core.Connectivity
  implicit val connectivity = DefaultConnectivity

  val service = new UpdateService(DefaultUpdateNotifier)

  import org.genivi.sota.core.data.UpdateRequest
  import org.scalatest.concurrent.ScalaFutures.{whenReady, PatienceConfig}

  val AvailablePackageIdGen = Gen.oneOf(packages).map( _.id )

  implicit val defaultPatience = PatienceConfig(timeout = Span(1, Second), interval = Span(500, Millis))

  implicit override val generatorDrivenConfig = PropertyCheckConfig(minSuccessful = 20)

  property("decline if package not found") {
    forAll(updateRequestGen(PackageIdGen)) { (request: UpdateRequest) =>
      whenReady( service.queueUpdate( request, _ => FastFuture.successful( Map.empty ) ).failed ) { e =>
        e shouldBe PackagesNotFound( request.packageId )
      }
    }
  }

  property("decline if some of dependencies not found") {
    def vinDepGen(missingPackages: Seq[PackageId]) : Gen[(Vehicle.Vin, Set[PackageId])] = for {
      vin               <- VehicleGenerators.genVin
      m                 <- Gen.choose(1, 10)
      availablePackages <- Gen.pick(m, packages).map( _.map(_.id) )
      n                 <- Gen.choose(1, missingPackages.length)
      deps              <- Gen.pick(n, missingPackages).map( xs => Random.shuffle(availablePackages ++ xs).toSet )
    } yield vin -> deps

    val resolverGen : Gen[(Seq[PackageId], UpdateService.DependencyResolver)] = for {
      n                 <- Gen.choose(1, 10)
      missingPackages   <- Gen.listOfN(n, PackageIdGen).map( _.toSeq )
      m                 <- Gen.choose(1, 10)
      vinsToDeps        <- Gen.listOfN(m, vinDepGen(missingPackages)).map( _.toMap )
    } yield (missingPackages, (_: Package) => FastFuture.successful(vinsToDeps))

    forAll(updateRequestGen( AvailablePackageIdGen ), resolverGen) { (request, resolverConf) =>
      val (missingPackages, resolver) = resolverConf
      whenReady(service.queueUpdate(request, resolver).failed.mapTo[PackagesNotFound]) { failure =>
        failure.packageIds.toSet.union(missingPackages.toSet) should contain theSameElementsAs missingPackages
      }
    }
  }

  def createVehicles( vins: Set[Vehicle.Vin] ) : Future[Unit] = {
    import slick.driver.MySQLDriver.api._
    db.run( DBIO.seq( vins.map( vin => Vehicles.create(Vehicle(vin))).toArray: _* ) )
  }

  property("upload spec per vin") {
    import slick.driver.MySQLDriver.api._
    import scala.concurrent.duration.DurationInt
    forAll( updateRequestGen(AvailablePackageIdGen), dependenciesGen(packages) ) { (request, deps) =>
      whenReady( createVehicles(deps.keySet).flatMap( _ => service.queueUpdate(request, _ => Future.successful(deps))) ) { specs =>
        val updates = Await.result( db.run( UpdateSpecs.listUpdatesById( Refined.unsafeApply( request.id.toString ) ) ), 1.second)
        specs.size shouldBe deps.size
      }
    }
  }

  property("queue an update for a single vehicle creates an update request") {
    val newVehicle = VehicleGenerators.genVehicle.sample.get
    val newPackage = PackageGen.sample.get

    val dbSetup = for {
      vin <- Vehicles.create(newVehicle)
      packageM <- Packages.create(newPackage)
    } yield (vin, packageM)

    val f = for {
      (vin, packageM) <- db.run(dbSetup)
      updateRequest <- service.queueVehicleUpdate(vin, packageM.id)
      queuedPackages <- db.run(UpdateSpecs.getPackagesQueuedForVin(vin))
    } yield (updateRequest, queuedPackages)

    whenReady(f) { case (updateRequest, queuedPackages) =>
      updateRequest.packageId shouldBe newPackage.id
      queuedPackages should contain(newPackage.id)
    }
  }


  property("create update campaign for vehicles in resolver's but not in core's database") {
    // create a package whose id matches that in the UpdateRequest
    val pkg = PackageGen.sample.get
    val request = updateRequestGen(PackageIdGen).sample.get.copy(packageId = pkg.id)

    // fabricate a resolver mapping a single vehicle (not in core's db) to a package in core's db
    val vehicle = VehicleGenerators.genVehicle.sample.get
    val depResolver: UpdateService.DependencyResolver = _ => FastFuture.successful( Map(vehicle.vin -> Set(pkg.id)) )

    val steps = for (
      vehicleWasMissing <- db.run(Vehicles.exists(vehicle.vin));
      // persist the package, will be loaded by the method under test, service.queueUpdate()
      _ <- db.run(Packages.create(pkg));
      _ <- service.queueUpdate( request, depResolver );
      optVin <- db.run(Vehicles.exists(vehicle.vin))
    ) yield (vehicleWasMissing, optVin)

    // make sure the vehicle was added to core's db
    whenReady( steps ) { res =>
      val (vehicleWasMissing, optVin) = res
      vehicleWasMissing.isEmpty shouldBe true
      optVin.isDefined shouldBe true
    }
  }

  override def afterAll() : Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }
}
