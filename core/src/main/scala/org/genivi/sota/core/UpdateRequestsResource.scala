package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.http.scaladsl.server.PathMatchers.Slash
import akka.http.scaladsl.server.Directives
import Directives._
import akka.stream.ActorMaterializer
import eu.timepit.refined._
import eu.timepit.refined.string._
import io.circe.generic.auto._
import org.genivi.sota.marshalling.CirceMarshallingSupport
import akka.http.scaladsl.marshalling.Marshaller._
import org.genivi.sota.core.data._
import org.genivi.sota.data.PackageId
import org.genivi.sota.rest.Validation._
import slick.driver.MySQLDriver.api.Database
import io.circe.syntax._
import org.genivi.sota.core.resolver.ExternalResolverClient
import org.genivi.sota.core.db.UpdateSpecs

class UpdateRequestsResource(db: Database, resolver: ExternalResolverClient, updateService: UpdateService)
                            (implicit system: ActorSystem, mat: ActorMaterializer) {

  import UpdateSpec._
  import CirceMarshallingSupport._
  import system.dispatcher
  import eu.timepit.refined.string.uuidValidate

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
          val result = updateService.queueVehicleUpdate(vin, packageId)
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
                pkg => resolver.resolve(pkg.id).map {
                  m => m.map { case (v, p) => (v.vin, p) }
                }
              )
            )
          }
        }
    }
  }
}
