/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core


import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{Directive1, Directives}
import akka.stream.ActorMaterializer
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string._
import java.util.UUID
import org.genivi.sota.core.common.NamespaceDirective
import org.genivi.sota.core.resolver.{Connectivity, ExternalResolverClient}
import org.genivi.sota.core.transfer.UpdateNotifier
import org.genivi.sota.data.Device
import org.genivi.sota.data.Namespace._
import org.genivi.sota.rest.Validation.refined
import slick.driver.MySQLDriver.api.Database


object WebService {
  import Directives._

  val extractUuid = refined[Uuid](Slash ~ Segment)
  def toUUID(uuid: Refined[String, Uuid]): UUID = UUID.fromString(uuid.get)
}

class WebService(notifier: UpdateNotifier, resolver: ExternalResolverClient, db : Database)
                (implicit val system: ActorSystem, val mat: ActorMaterializer,
                 connectivity: Connectivity) extends Directives {

  implicit val log = Logging(system, "webservice")

  import ErrorHandler._

  val devices = new DevicesResource(db, connectivity.client, resolver)
  val packages = new PackagesResource(resolver, db)
  val updateRequests = new UpdateRequestsResource(db, resolver, new UpdateService(notifier))
  val history = new HistoryResource(db)

  val route = (handleErrors & pathPrefix("api" / "v1")) {
    devices.route ~ packages.route ~ updateRequests.route ~ history.route
  }
}
