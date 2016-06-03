/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directive1, Directives, Route}
import akka.stream.ActorMaterializer
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string._
import io.circe.generic.auto._
import io.circe.syntax._
import org.genivi.sota.common.IDeviceRegistry
import org.genivi.sota.core.data._
import org.genivi.sota.core.resolver.{ConnectivityClient, ExternalResolverClient}
import org.genivi.sota.datatype.Namespace._
import org.genivi.sota.data.Vehicle
import org.genivi.sota.marshalling.CirceMarshallingSupport
import org.genivi.sota.rest.Validation._
import org.genivi.sota.marshalling.RefinedMarshallingSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshaller

import scala.util.{Failure, Success}
import scala.languageFeature.implicitConversions
import scala.languageFeature.postfixOps
import slick.driver.MySQLDriver.api.Database

import scala.concurrent.Future

class VehiclesResource(db: Database, client: ConnectivityClient,
                       resolverClient: ExternalResolverClient,
                       deviceRegistry: IDeviceRegistry,
                       namespaceExtractor: Directive1[Namespace])
                      (implicit system: ActorSystem, mat: ActorMaterializer) {

  import CirceMarshallingSupport._
  import Directives._
  import WebService._
  import system.dispatcher

  implicit val _db = db

  case object MissingVehicle extends Throwable

  type RefinedRegx = Refined[String, Regex]

  /**
    * An ota client GET a Seq of [[Vehicle]] from regex/status search.
    */
  def search(ns: Namespace): Route = {
    parameters(('status.?(false), 'regex.as[RefinedRegx].?)) {
      (includeStatus: Boolean, reqRegex: Option[RefinedRegx]) =>
        val regex = reqRegex.getOrElse(Refined.unsafeApply(".*"))
        val devices = deviceRegistry.searchDevice(ns, regex)

        if (includeStatus) {
          completeWith(VehicleSearch.fetchDeviceStatus(devices))
        } else {
          completeWith(devices)
        }
    }
  }

  protected def completeWith[T](searchResult: Future[Seq[T]])(implicit ev: ToResponseMarshaller[Seq[T]]): Route = {
    onComplete(searchResult) {
      case Success(ds) => complete(ds)
      case Failure(ex) => extractLog { log =>
        log.error(ex, "cannot lookup update status for devices")
        complete((StatusCodes.InternalServerError, s"cannot lookup update status for devices: ${ex.getMessage}"))
      }
    }
  }

  val route =
    (pathPrefix("vehicles") & namespaceExtractor) { ns =>
      (pathEnd & get) { search(ns) }
    }
}
