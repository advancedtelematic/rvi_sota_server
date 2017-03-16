/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server._
import io.circe.generic.auto._
import org.genivi.sota.core.data.TargetInfo
import org.genivi.sota.core.db.MultiTargetUpdates
import org.genivi.sota.data.Uuid
import slick.driver.MySQLDriver.api.Database

class MultiTargetUpdatesResource()(implicit db: Database, system: ActorSystem) {

  import Directives._
  import org.genivi.sota.http.ErrorHandler._
  import org.genivi.sota.http.UuidDirectives._
  import org.genivi.sota.marshalling.CirceMarshallingSupport._

  implicit val ec = system.dispatcher
  implicit val _db = db
  implicit val _config = system.settings.config

  def createTargetInfo(): Route =
    entity(as[TargetInfo]) { targetInfo =>
      complete(Created -> db.run(MultiTargetUpdates.create(targetInfo)))
    }

  def getTargetInfo(target: Uuid): Route =
    complete(db.run(MultiTargetUpdates.fetch(target)))

  val route = handleErrors {
    pathPrefix("multi_target_updates") {
      post {
        createTargetInfo()
      } ~
      (extractUuid & get) { uuid =>
        getTargetInfo(uuid)
      }
    }
  }
}
