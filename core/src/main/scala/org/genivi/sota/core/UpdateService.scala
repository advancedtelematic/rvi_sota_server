/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core

import akka.event.LoggingAdapter
import akka.http.scaladsl.util.FastFuture
import cats.Show
import org.genivi.sota.core.data._
import org.genivi.sota.core.db._
import org.genivi.sota.core.transfer.UpdateNotifier
import org.genivi.sota.data.{PackageId, Vehicle}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace
import slick.dbio.DBIO
import slick.driver.MySQLDriver.api.Database

import scala.collection.immutable.ListSet
import scala.concurrent.{ExecutionContext, Future}

case class PackagesNotFound(packageIds: (PackageId)*)
                           (implicit show: Show[PackageId])
    extends Throwable(s"""Package(s) not found: ${packageIds.map(show.show).mkString(", ")}""") with NoStackTrace

case class UploadConf( chunkSize: Int, downloadSplitStrategy: Set[Package] => Vector[Download] )

object UploadConf {

  implicit val default = UploadConf(
    chunkSize = 64 * 1024,
    downloadSplitStrategy = packages => {
      packages.map(p => Download.apply(Vector(p))).toVector
    }
  )

}

class UpdateService(notifier: UpdateNotifier)
                   (implicit val log: LoggingAdapter, val connectivity: Connectivity) {
  import UpdateService._

  def mapIdsToPackages(vinsToDeps: VinsToPackages )
                      (implicit db: Database, ec: ExecutionContext): Future[Map[PackageId, Package]] = {
    def mapPackagesToIds( packages: Seq[Package] ) : Map[PackageId, Package] = packages.map( x => x.id -> x).toMap

    def missingPackages( required: Set[PackageId], found: Seq[Package] ) : Set[PackageId] = {
      val result = required -- found.map( _.id )
      if( result.nonEmpty ) log.debug( s"Some of required packages not found: $result" )
      result
    }

    log.debug(s"Dependencies from resolver: $vinsToDeps")
    val requirements : Set[PackageId]  =
      vinsToDeps.foldLeft(Set.empty[PackageId])((acc, vinDeps) => acc.union(vinDeps._2) )
    for {
      foundPackages <- db.run( Packages.byIds( requirements ) )
      mapping       <- if( requirements.size == foundPackages.size ) {
                         FastFuture.successful( mapPackagesToIds( foundPackages ) )
                       } else {
                         FastFuture.failed( PackagesNotFound( missingPackages(requirements, foundPackages).toArray: _*))
                       }
    } yield mapping

  }

  def loadPackage( id : PackageId)
                 (implicit db: Database, ec: ExecutionContext): Future[Package] = {
    db.run(Packages.byId(id)).flatMap { x =>
      x.fold[Future[Package]](FastFuture.failed( PackagesNotFound(id)) )(FastFuture.successful)
    }
  }

  def mkUploadSpecs(request: UpdateRequest, vinsToPackageIds: VinsToPackages,
                    idsToPackages: Map[PackageId, Package]): Set[UpdateSpec] = {
    vinsToPackageIds.map {
      case (vin, requiredPackageIds) =>
        val packages : Set[Package] = requiredPackageIds.map( idsToPackages.get ).map( _.get )
        UpdateSpec( request, vin, UpdateStatus.Pending, packages)
    }.toSet
  }

  /**
    * Tables modified:
    * - UpdateRequest (insert one row), UpdateSpec (insert many rows).
    * - Vehicle (insert one row for each missing vehicle)
    */
  def persistRequest(request: UpdateRequest, updateSpecs: Set[UpdateSpec])
                    (implicit db: Database, ec: ExecutionContext) : Future[Unit] = {

    val dbIO = DBIO.seq(
      Vehicles.insertMissing(updateSpecs.map(_.vin).toSeq),
      UpdateRequests.persist(request),
      DBIO.sequence(updateSpecs.map(UpdateSpecs.persist).toSeq)
    )

    db.run(dbIO)
  }

  /**
    * From the given [[UpdateRequest]] the resolver prepares a map listing for each VIN its package dependencies.
    * From that map a set of [[UpdateSpec]] is derived and persisted in core's db.
    * The situation where resolver-provided VINs don't exist in core is handled by inserting them in core db.
    */
  def queueUpdate(request: UpdateRequest, resolver : DependencyResolver )
                 (implicit db: Database, ec: ExecutionContext): Future[Set[UpdateSpec]] = {
    for {
      pckg           <- loadPackage(request.packageId)
      vinsToDeps     <- resolver(pckg)
      packages       <- mapIdsToPackages(vinsToDeps)
      updateSpecs    = mkUploadSpecs(request, vinsToDeps, packages)
      _              <- persistRequest(request, updateSpecs)
      _              <- Future.successful(notifier.notify(updateSpecs.toSeq))
    } yield updateSpecs
  }

  def queueVehicleUpdate(vin: Vehicle.Vin, packageId: PackageId)
                        (implicit db: Database, ec: ExecutionContext): Future[UpdateRequest] = {
    val newUpdateRequest = UpdateRequest.default(packageId)

    for {
      p <- loadPackage(packageId)
      updateRequest = newUpdateRequest.copy(signature = p.signature.getOrElse(newUpdateRequest.signature),
        description = p.description)
      spec = UpdateSpec(updateRequest, vin, UpdateStatus.Pending, Set.empty)
      dbSpec <- persistRequest(updateRequest, ListSet(spec))
    } yield updateRequest
  }

  def all(implicit db: Database, ec: ExecutionContext): Future[Set[UpdateRequest]] =
    db.run(UpdateRequests.list).map(_.toSet)
}

object UpdateService {
  type VinsToPackages = Map[Vehicle.Vin, Set[PackageId]]
  type DependencyResolver = Package => Future[VinsToPackages]

}
