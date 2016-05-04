/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.filters

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import io.circe.generic.auto._
import org.genivi.sota.data.Namespace._
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.marshalling.RefinedMarshallingSupport._
import org.genivi.sota.resolver.common.Errors
import org.genivi.sota.resolver.common.NamespaceDirective._
import org.genivi.sota.resolver.packages.PackageFilterRepository
import org.genivi.sota.rest.Validation._
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.JdbcBackend.Database


/**
 * API routes for filters.
 * @see {@linktourl http://pdxostc.github.io/rvi_sota_server/dev/api.html}
 */
class FilterDirectives(implicit system: ActorSystem,
                       db: Database,
                       mat: ActorMaterializer,
                       ec: ExecutionContext) {

  case class FilterWithoutNs(
    name: Filter.Name,
    expression: Filter.Expression)

  def extractFilter(ns: Namespace): Directive1[Filter] = entity(as[FilterWithoutNs]).flatMap { filter =>
    Directives.provide(Filter(namespace = ns,
                              name = filter.name,
                              expression = filter.expression))
  }

  def searchFilter(ns: Namespace): Route =
    parameter('regex.as[String Refined Regex].?) { re =>
      val query = re.fold(FilterRepository.list)(re => FilterRepository.searchByRegex(ns, re))
      complete(db.run(query))
    }

  def getPackages(ns: Namespace, fname: String Refined Filter.ValidName): Route =
    completeOrRecoverWith(db.run(PackageFilterRepository.listPackagesForFilter(ns, fname))) {
      Errors.onMissingFilter
    }

  def createFilter(ns: Namespace): Route =
    extractFilter(ns) { filter =>
      complete(db.run(FilterRepository.add(filter)))
    }

  def updateFilter(ns: Namespace, fname: String Refined Filter.ValidName): Route =
    entity(as[Filter.ExpressionWrapper]) { expr =>
      complete(db.run(FilterRepository.update(Filter(ns, fname, expr.expression))))
    }

  def deleteFilter(ns: Namespace, fname: String Refined Filter.ValidName): StandardRoute =
    complete(db.run(FilterRepository.deleteFilterAndPackageFilters(ns, fname)))

  /**
   * API route for validating filters.
   * @return      Route object containing routes for verifying that a filter is valid
   */
  def validateFilter(ns: Namespace): Route =
    extractFilter(ns) { filter =>
      complete("OK")
    }

  /**
   * API route for filters.
   * @return      Route object containing routes for getting, creating,
   *              editing, deleting, and validating filters
   * @throws      Errors.MissingFilterException if the filter doesn't exist
   */
  def route: Route =
    handleExceptions(ExceptionHandler(Errors.onMissingFilter orElse Errors.onMissingPackage)) {
      extractNamespace(system) { ns =>
        pathPrefix("filters") {
          (get & pathEnd) {
            searchFilter(ns)
          } ~
          (get & refined[Filter.ValidName](Slash ~ Segment) & path("package")) { fname =>
            getPackages(ns, fname)
          } ~
          (post & pathEnd) {
            createFilter(ns)
          } ~
          (put & refined[Filter.ValidName](Slash ~ Segment) & pathEnd) { fname =>
            updateFilter(ns, fname)
          } ~
          (delete & refined[Filter.ValidName](Slash ~ Segment) & pathEnd) { fname =>
            deleteFilter(ns, fname)
          }
        } ~
        (post & path("validate" / "filter")) {
          validateFilter(ns)
        }
      }
    }

}
