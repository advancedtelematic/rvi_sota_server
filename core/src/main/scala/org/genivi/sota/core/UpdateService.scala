/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.util.FastFuture
import cats.Show
import eu.timepit.refined._
import eu.timepit.refined.string._
import org.genivi.sota.core.data._
import org.genivi.sota.core.db._
import org.genivi.sota.core.resolver.Connectivity
import org.genivi.sota.core.rvi.ServerServices
import org.genivi.sota.core.transfer.UpdateNotifier
import org.genivi.sota.datatype.Namespace._
import org.genivi.sota.data.{PackageId, Vehicle}
import org.joda.time.DateTime

import scala.collection.immutable.ListSet
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace
import slick.dbio.DBIO
import slick.driver.MySQLDriver.api.Database


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
                   (implicit val system: ActorSystem, val connectivity: Connectivity) {

  import UpdateService._

  implicit private val log = Logging(system, "updateservice")

  def checkVins( dependencies: VinsToPackageIds ) : Future[Boolean] = FastFuture.successful( true )

  /**
    * Fetch from DB the [[Package]]s corresponding to the given [[PackageId]]s,
    * failing in case not all could be fetched.
    */
  def fetchPackages(ns: Namespace, requirements: Set[PackageId] )
                   (implicit db: Database, ec: ExecutionContext): Future[Seq[Package]] = {

    def missingPackages( required: Set[PackageId], found: Seq[Package] ) : Set[PackageId] = {
      val result = required -- found.map( _.id )
      if( result.nonEmpty ) log.debug( s"Some of required packages not found: $result" )
      result
    }

    for {
      foundPackages <- db.run(Packages.byIds(ns, requirements))
      mapping       <- if( requirements.size == foundPackages.size ) {
                         FastFuture.successful( foundPackages )
                       } else {
                         FastFuture.failed( PackagesNotFound( missingPackages(requirements, foundPackages).toArray: _*))
                       }
    } yield mapping

  }

  def loadPackage(ns: Namespace, id : PackageId)
                 (implicit db: Database, ec: ExecutionContext): Future[Package] = {
    db.run(Packages.byId(ns, id)).flatMap { x =>
      x.fold[Future[Package]](FastFuture.failed( PackagesNotFound(id)) )(FastFuture.successful)
    }
  }

  /**
    * For each of the given (VIN, dependencies) prepare an [[UpdateSpec]]
    * that points to the given [[UpdateRequest]] and has [[UpdateStatus]] "Pending".
    * <p>
    * No install order is specified for the single [[UpdateSpec]] that is prepared per VIN.
    * However, a timestamp is included in each [[UpdateSpec]] to break ties
    * with any other (already persisted) [[UpdateSpec]]s that might be pending.
    *
    * @param vinsToPackageIds several VIN-s and the dependencies for each of them
    * @param idsToPackages lookup a [[Package]] by its [[PackageId]]
    */
  def mkUpdateSpecs(request: UpdateRequest,
                    vinsToPackageIds: VinsToPackageIds,
                    idsToPackages: Map[PackageId, Package]): Set[UpdateSpec] = {
    vinsToPackageIds.map {
      case (vin, requiredPackageIds) =>
        UpdateSpec(request, vin, UpdateStatus.Pending, requiredPackageIds map idsToPackages, 0, DateTime.now)
    }.toSet
  }

  def persistRequest(request: UpdateRequest, updateSpecs: Set[UpdateSpec])
                    (implicit db: Database, ec: ExecutionContext) : Future[Unit] = {
    db.run(
      DBIO.seq(UpdateRequests.persist(request) +: updateSpecs.map( UpdateSpecs.persist ).toArray: _*)).map( _ => ()
    )
  }

  /**
    * <ul>
    *   <li>For the [[Package]] of the given [[UpdateRequest]] find the vehicles where it needs to be installed,</li>
    *   <li>For each such VIN create an [[UpdateSpec]]</li>
    *   <li>Persist in DB all of the above</li>
    * </ul>
    */
  def queueUpdate(request: UpdateRequest, resolver : DependencyResolver )
                 (implicit db: Database, ec: ExecutionContext): Future[Set[UpdateSpec]] = {
    val ns = request.namespace
    for {
      pckg           <- loadPackage(ns, request.packageId)
      vinsToDeps     <- resolver(pckg)
      requirements    = gatherAllRequirements(vinsToDeps)
      packages       <- fetchPackages(ns, requirements)
      idsToPackages   = packages.map( x => x.id -> x ).toMap
      updateSpecs     = mkUpdateSpecs(request, vinsToDeps, idsToPackages)
      _              <- persistRequest(request, updateSpecs)
      _              <- Future.successful(notifier.notify(updateSpecs.toSeq))
    } yield updateSpecs
  }

  /**
    * Gather all [[PackageId]]s (dependencies) across all given VINs.
    */
  def gatherAllRequirements(vinsToDeps: Map[Vehicle.Vin, Set[PackageId]]): Set[PackageId] = {
    log.debug(s"Dependencies from resolver: $vinsToDeps")
    vinsToDeps.foldLeft(Set.empty[PackageId])((acc, vinDeps) => acc.union(vinDeps._2) )
  }

  /**
    * For the given [[PackageId]] and vehicle, persist a fresh [[UpdateRequest]] and a fresh [[UpdateSpec]].
    * Resolver is not contacted.
    */
  def queueVehicleUpdate(ns: Namespace, vin: Vehicle.Vin, packageId: PackageId)
                        (implicit db: Database, ec: ExecutionContext): Future[UpdateRequest] = {
    val newUpdateRequest = UpdateRequest.default(ns, packageId)

    for {
      p <- loadPackage(ns, packageId)
      updateRequest = newUpdateRequest.copy(signature = p.signature.getOrElse(newUpdateRequest.signature),
        description = p.description)
      spec = UpdateSpec.default(updateRequest, vin)
      dbSpec <- persistRequest(updateRequest, ListSet(spec))
    } yield updateRequest
  }

  def all(implicit db: Database, ec: ExecutionContext): Future[Set[UpdateRequest]] =
    db.run(UpdateRequests.list).map(_.toSet)
}

object UpdateService {
  type VinsToPackageIds = Map[Vehicle.Vin, Set[PackageId]]
  type DependencyResolver = Package => Future[VinsToPackageIds]
}
