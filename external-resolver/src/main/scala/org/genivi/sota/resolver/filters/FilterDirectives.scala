/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.filters

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import eu.timepit.refined.Refined
import eu.timepit.refined.string.Regex
import io.circe.generic.auto._
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.marshalling.RefinedMarshallingSupport._
import org.genivi.sota.resolver.common.Errors
import org.genivi.sota.resolver.packages._
import org.genivi.sota.rest.Validation._
import org.genivi.sota.rest.{ErrorCode, ErrorRepresentation}
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.JdbcBackend.Database


object FutureSupport {

  implicit class FutureOps[T]( x: Future[Option[T]] ) {

    def failIfNone( t: Throwable )
                  (implicit ec: ExecutionContext): Future[T] =
      x.flatMap( _.fold[Future[T]]( FastFuture.failed(t) )(FastFuture.successful) )
  }
}

class FilterDirectives(implicit db: Database, mat: ActorMaterializer, ec: ExecutionContext) {
  import FutureSupport._

  def filterRoute: Route =
    pathPrefix("filters") {
      get {
        parameter('regex.as[Refined[String, Regex]].?) { re =>
          val query = re.fold(FilterRepository.list)(re => FilterRepository.searchByRegex(re))
          complete(db.run(query))
        }
      } ~
      (post & entity(as[Filter])) { filter =>
        complete(db.run(FilterRepository.add(filter)))
      } ~
      (put & refined[Filter.ValidName](Slash ~ Segment ~ PathEnd)
           & entity(as[Filter.ExpressionWrapper]))
      { (fname, expr) =>
        complete(db.run(FilterRepository.update(Filter(fname, expr.expression))).failIfNone( Errors.MissingFilterException ))
      } ~
      (delete & refined[Filter.ValidName](Slash ~ Segment ~ PathEnd))
      { fname =>
        complete(FilterFunctions.deleteFilterAndPackageFilters(fname))
      }

    }

  def packageFiltersRoute: Route =

    pathPrefix("packageFilters") {
      get {
        parameters('package.as[Package.NameVersion].?, 'filter.as[Filter.Name].?) {
          case (Some(nameVersion), None) =>
            val packageName: Package.Name = Refined(nameVersion.get.split("-").head)
            val packageVersion: Package.Version = Refined(nameVersion.get.split("-").tail.head)
            val f: Future[Seq[Filter]] = for {
              (p, fs) <- db.run(PackageFilterRepository.listFiltersForPackage(Package.Id(packageName, packageVersion)))
              _       <- p.fold[Future[Package]]( FastFuture.failed( Errors.MissingPackageException ) )( FastFuture.successful )
            } yield fs
            complete(f)

          case (None, Some(fname)) =>
            complete(PackageFunctions.listPackagesForFilter(fname))
          case (None, None) =>
            complete(db.run(PackageFilterRepository.list))
          case _ =>
            complete(StatusCodes.NotFound)
        }
      } ~
      (post & entity(as[PackageFilter])) { pf =>
        complete(PackageFunctions.addPackageFilter(pf))
      } ~
      (delete & refined[Package.ValidName]   (Slash ~ Segment)
              & refined[Package.ValidVersion](Slash ~ Segment)
              & refined[Filter.ValidName]    (Slash ~ Segment ~ PathEnd)) { (pname, pversion, fname) =>
        completeOrRecoverWith(PackageFunctions.deletePackageFilter(PackageFilter(pname, pversion, fname))) {
          case PackageFunctions.MissingPackageFilterException =>
            complete(StatusCodes.NotFound ->
              ErrorRepresentation( ErrorCode("filter_not_found"),
                s"No filter with the name '$fname' defined for package $pname-$pversion" ))
          case e                                              => failWith(e)
        }
      }
    }

  def validateRoute: Route = {
    pathPrefix("validate") {
      path("filter") ((post & entity(as[Filter])) (_ => complete("OK")))
    }
  }

  def route: Route =
    handleExceptions( ExceptionHandler( Errors.onMissingFilter orElse Errors.onMissingPackage ) ) {
      filterRoute ~ packageFiltersRoute ~ validateRoute
    }

}
