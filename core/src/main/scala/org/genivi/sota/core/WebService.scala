/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.PathMatchers.Slash
import akka.http.scaladsl.server.{Directive1, Directives, ExceptionHandler}
import Directives._
import akka.stream.ActorMaterializer
import eu.timepit.refined._
import eu.timepit.refined.string._
import io.circe.generic.auto._
import org.genivi.sota.core.common.Namespaces
import org.genivi.sota.core.transfer.UpdateNotifier
import org.genivi.sota.marshalling.CirceMarshallingSupport
import akka.http.scaladsl.marshalling.Marshaller._
import org.genivi.sota.core.data._
import org.genivi.sota.core.db.{InstallHistories, UpdateSpecs, Vehicles}
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.{PackageId, Vehicle}
import org.genivi.sota.rest.Validation._
import org.genivi.sota.rest.{ErrorCode, ErrorRepresentation}
import org.joda.time.DateTime
import slick.driver.MySQLDriver.api.Database
import slick.driver.MySQLDriver.api._
import scala.concurrent.{ExecutionContext, Future}
import io.circe.syntax._

object ErrorCodes {
  val ExternalResolverError = ErrorCode( "external_resolver_error" )

  val MissingVehicle = new ErrorCode("missing_vehicle")
}

object VehiclesResource {
  val extractVin : Directive1[Vehicle.Vin] = refined[Vehicle.ValidVin](Slash ~ Segment)
}

class VehiclesResource(db: Database, client: ConnectivityClient, resolverClient: ExternalResolverClient)
                      (implicit system: ActorSystem, mat: ActorMaterializer)
                      extends Namespaces {

  import system.dispatcher
  import CirceMarshallingSupport._
  import VehiclesResource._

  implicit val _db = db

  case object MissingVehicle extends Throwable

  def exists
    (vehicle: Vehicle)
    (implicit ec: ExecutionContext): Future[Vehicle] =
    db.run(Vehicles.exists(vehicle))
      .flatMap(_
        .fold[Future[Vehicle]]
          (Future.failed(MissingVehicle))(Future.successful(_)))

  def deleteVin (vehicle: Vehicle)
  (implicit ec: ExecutionContext): Future[Unit] =
    for {
      _ <- exists(vehicle)
      _ <- db.run(UpdateSpecs.deleteRequiredPackageByVin(extractNamespace, vehicle))
      _ <- db.run(UpdateSpecs.deleteUpdateSpecByVin(extractNamespace, vehicle))
      _ <- db.run(Vehicles.deleteById(vehicle))
    } yield ()

  def ttl() : DateTime = {
    import com.github.nscala_time.time.Implicits._
    DateTime.now + 5.minutes
  }

  val route = pathPrefix("vehicles") {
    WebService.extractVin { vin =>
      pathEnd {
        get {
          completeOrRecoverWith(exists(Vehicle(extractNamespace, vin))) {
            case MissingVehicle =>
              complete(StatusCodes.NotFound ->
                ErrorRepresentation(ErrorCodes.MissingVehicle, "Vehicle doesn't exist"))
          }
        } ~
        put {
          complete(db.run(Vehicles.create(Vehicle(extractNamespace, vin))).map(_ => NoContent))
        } ~
        delete {
          completeOrRecoverWith(deleteVin(Vehicle(extractNamespace, vin))) {
            case MissingVehicle =>
              complete(StatusCodes.NotFound ->
                ErrorRepresentation(ErrorCodes.MissingVehicle, "Vehicle doesn't exist"))
          }
        }
      } ~
      // TODO: Check that vin exists
      (path("queued") & get) {
        complete(db.run(UpdateSpecs.getPackagesQueuedForVin(extractNamespace, vin)))
      } ~
      (path("history") & get) {
        complete(db.run(InstallHistories.list(extractNamespace, vin)))
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
          val resultIO = VehicleSearch.search(extractNamespace, regex, includeStatus)
          complete(db.run(resultIO))
        }
      }
    }
  }
}

class UpdateRequestsResource(db: Database, resolver: ExternalResolverClient, updateService: UpdateService)
                            (implicit system: ActorSystem, mat: ActorMaterializer)
                            extends Namespaces {
  import CirceMarshallingSupport._
  import UpdateSpec._
  import system.dispatcher

  implicit val _db = db

  val route = pathPrefix("updates") {
    (get & refined[Uuid](Slash ~ Segment ~ PathEnd)) { uuid =>
      complete(db.run(UpdateSpecs.listUpdatesById(uuid)))
    }
  } ~
  pathPrefix("updates") {
    VehiclesResource.extractVin { vin =>
      post {
        entity(as[PackageId]) { packageId =>
          val result = updateService.queueVehicleUpdate(extractNamespace, vin, packageId)
          complete(result)
        }
      }
    } ~
    pathEnd {
      get {
        complete(updateService.all(db, system.dispatcher))
      } ~
        post {
          entity(as[UpdateRequest]) { req =>
            complete(
              updateService.queueUpdate(
                req,
                pkg => resolver.resolve(extractNamespace, pkg.id).map {
                  m => m.map { case (v, p) => (v.vin, p) }
                }
              )
            )
          }
        }
    }
  }
}

object WebService {
  val extractVin : Directive1[Vehicle.Vin] = refined[Vehicle.ValidVin](Slash ~ Segment)
}

class WebService(notifier: UpdateNotifier, resolver: ExternalResolverClient, db : Database)
                (implicit val system: ActorSystem, val mat: ActorMaterializer,
                 connectivity: Connectivity) extends Directives {
  import io.circe.Json
  import Json.{obj, string}

  implicit private val log = Logging(system, "webservice")

  val exceptionHandler = ExceptionHandler {
    case e: Throwable =>
      extractUri { uri =>
        log.error(s"Request to $uri errored: $e")
        val entity = obj("error" -> string(e.getMessage()))
        complete(HttpResponse(InternalServerError, entity = entity.toString()))
      }
  }

  val vehicles = new VehiclesResource(db, connectivity.client, resolver)
  val packages = new PackagesResource(resolver, db)
  val updateRequests = new UpdateRequestsResource(db, resolver, new UpdateService(notifier))

  val route = pathPrefix("api" / "v1") {
    handleExceptions(exceptionHandler) {
      vehicles.route ~ packages.route ~ updateRequests.route
    }
  }
}
