package org.genivi.sota.core

import org.genivi.sota.core.resolver.ConnectivityClient
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.PathMatchers.Slash
import akka.http.scaladsl.server.{Directive1, Directives}
import Directives._
import akka.stream.ActorMaterializer
import eu.timepit.refined._
import eu.timepit.refined.string._
import io.circe.generic.auto._
import org.genivi.sota.marshalling.CirceMarshallingSupport
import akka.http.scaladsl.marshalling.Marshaller._
import org.genivi.sota.core.data._
import org.genivi.sota.core.db.{InstallHistories, UpdateSpecs, Vehicles}
import org.genivi.sota.data.Vehicle
import org.genivi.sota.rest.Validation._
import org.genivi.sota.rest.ErrorRepresentation
import org.joda.time.DateTime
import slick.driver.MySQLDriver.api.Database
import scala.concurrent.{ExecutionContext, Future}
import io.circe.syntax._
import org.genivi.sota.core.resolver.ExternalResolverClient

object VehiclesResource {
  val extractVin : Directive1[Vehicle.Vin] = refined[Vehicle.ValidVin](Slash ~ Segment)
}

class VehiclesResource(db: Database, client: ConnectivityClient, resolverClient: ExternalResolverClient)
                      (implicit system: ActorSystem, mat: ActorMaterializer) {

  import system.dispatcher
  import CirceMarshallingSupport._
  import VehiclesResource._
  import VehiclesResource._

  implicit val _db = db

  case object MissingVehicle extends Throwable

  def exists(vehicle: Vehicle)
    (implicit ec: ExecutionContext): Future[Vehicle] =
    db.run(Vehicles.exists(vehicle.vin))
      .flatMap(_
        .fold[Future[Vehicle]]
          (Future.failed(MissingVehicle))(Future.successful))

  def deleteVin (vehicle: Vehicle)
  (implicit ec: ExecutionContext): Future[Unit] =
    for {
      _ <- exists(vehicle)
      _ <- db.run(UpdateSpecs.deleteRequiredPackageByVin(vehicle))
      _ <- db.run(UpdateSpecs.deleteUpdateSpecByVin(vehicle))
      _ <- db.run(Vehicles.deleteById(vehicle))
    } yield ()


  def ttl() : DateTime = {
    import com.github.nscala_time.time.Implicits._
    DateTime.now + 5.minutes
  }

  val route = pathPrefix("vehicles") {
    extractVin { vin =>
      pathEnd {
        get {
          completeOrRecoverWith(exists(Vehicle(vin))) {
            case MissingVehicle =>
              complete(StatusCodes.NotFound ->
                ErrorRepresentation(ErrorCodes.MissingVehicle, "Vehicle doesn't exist"))
          }
        } ~
        put {
          complete(db.run(Vehicles.create(Vehicle(vin))).map(_ => NoContent))
        } ~
        delete {
          completeOrRecoverWith(deleteVin(Vehicle(vin))) {
            case MissingVehicle =>
              complete(StatusCodes.NotFound ->
                ErrorRepresentation(ErrorCodes.MissingVehicle, "Vehicle doesn't exist"))
          }
        }
      } ~
      // TODO: Check that vin exists
      (path("queued") & get) {
        complete(db.run(UpdateSpecs.getPackagesQueuedForVin(vin)))
      } ~
      (path("history") & get) {
        complete(db.run(InstallHistories.list(vin)))
      } ~
      (path("sync") & put) {
        // TODO: Config RVI destination path (or ClientServices.getpackages)
        client.sendMessage(s"genivi.org/vin/${vin}/sota/getpackages", io.circe.Json.Empty, ttl())
        // TODO: Confirm getpackages in progress to vehicle?
        complete(NoContent)
      }
    } ~
    pathEnd {
      get {
        parameters(('status.?(false), 'regex.?)) { (includeStatus: Boolean, regex: Option[String]) =>
          val resultIO = VehicleSearch.search(regex, includeStatus)
          complete(db.run(resultIO))
        }
      }
    }
  }
}
