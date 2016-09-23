/*
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */

package org.genivi.sota.core
import org.genivi.sota.core.db.BlacklistedPackages
import akka.actor.ActorSystem
import akka.http.scaladsl.server.{Directive1, Directives, Route}
import org.genivi.sota.data.{Namespace, PackageId, Uuid}
import io.circe.generic.auto._
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import slick.driver.MySQLDriver.api._
import Directives._
import org.genivi.sota.core.resolver.ExternalResolverClient
import org.genivi.sota.http.ErrorHandler
import scala.concurrent.Future

class ImpactResource(namespaceExtractor: Directive1[Namespace],
                     authToken: Directive1[Option[String]],
                     resolverClient: ExternalResolverClient)
                    (implicit db: Database, system: ActorSystem) {
  import system.dispatcher

  def affectedDevicesFn(token: Option[String], ns: Namespace)(pkgs: Set[PackageId]): Future[Map[Uuid, Seq[PackageId]]] =
    resolverClient.affectedDevices(ns, pkgs).withToken(token).exec

  def runImpactAnalysis(namespace: Namespace): Route = authToken { token =>
    val f = BlacklistedPackages.impact(namespace, affectedDevicesFn(token, namespace))
    complete(f)
  }

  val route = (ErrorHandler.handleErrors & pathPrefix("impact"))  {
    (get & path("blacklist") & namespaceExtractor) { ns =>
      runImpactAnalysis(ns)
    }
  }
}
