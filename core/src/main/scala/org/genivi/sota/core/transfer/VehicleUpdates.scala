/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.transfer


import java.util.UUID

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import io.circe.Json
import io.circe.syntax._
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.{PackageId, Vehicle}
import org.genivi.sota.core.data._
import org.genivi.sota.core.db.{InstallHistories, OperationResults, UpdateSpecs}
import org.genivi.sota.db.SlickExtensions
import slick.dbio.DBIO
import slick.driver.MySQLDriver.api._
import org.genivi.sota.core.db.UpdateSpecs._
import org.genivi.sota.core.resolver.ExternalResolverClient
import org.genivi.sota.core.rvi.UpdateReport

import scala.concurrent.{ExecutionContext, Future}
import org.genivi.sota.refined.SlickRefined._
import org.joda.time.DateTime

import scala.util.control.NoStackTrace
import org.genivi.sota.core.db.OperationResults
import org.genivi.sota.core.db.InstallHistories


object VehicleUpdates {
  import SlickExtensions._

  case class UpdateSpecNotFound(msg: String) extends Exception(msg) with NoStackTrace
  case class SetOrderFailed(msg: String) extends Exception(msg) with NoStackTrace

  /**
    * Tell resolver to record the given packages as installed on a vehicle, overwriting any previous such list.
    */
  def update(vin: Vehicle.Vin, packageIds: List[PackageId], resolverClient: ExternalResolverClient): Future[Unit] = {
    // TODO: core should be able to send this instead!
    val j = Json.obj("packages" -> packageIds.asJson, "firmware" -> Json.arr())
    resolverClient.setInstalledPackages(vin, j)
  }

  def buildReportInstallResponse(vin: Vehicle.Vin, updateReport: UpdateReport)
                                (implicit ec: ExecutionContext, db: Database): Future[HttpResponse] = {
    reportInstall(vin, updateReport) map { _ =>
      HttpResponse(StatusCodes.NoContent)
    } recover { case t: UpdateSpecNotFound =>
      HttpResponse(StatusCodes.NotFound, entity = t.getMessage)
    }
  }

  /**
    * <ul>
    *   <li>Persist in [[OperationResults.OperationResultTable]] each operation result
    *       for a combination of ([[UpdateRequest]], VIN)</li>
    *   <li>Persist the status of the [[UpdateSpec]] as Finished</li>
    *   <li>Persist the outcome in [[InstallHistories.InstallHistoryTable]]</li>
    * </ul>
    */
  def reportInstall(vin: Vehicle.Vin, updateReport: UpdateReport)
                   (implicit ec: ExecutionContext, db: Database): Future[UpdateSpec] = {

    val writeResultsIO = (ns: Namespace) => for {
      rviOpResult <- updateReport.operation_results
      dbOpResult   = org.genivi.sota.core.data.OperationResult.from(
        rviOpResult,
        updateReport.update_id,
        vin,
        ns)
    } yield OperationResults.persist(dbOpResult)

    val dbIO = for {
      spec <- findUpdateSpecFor(vin, updateReport.update_id)
      _ <- DBIO.sequence(writeResultsIO(spec.namespace))
      _ <- UpdateSpecs.setStatus(spec, UpdateStatus.Finished)
      _ <- InstallHistories.log(
             spec.namespace, vin,
             spec.request.id, spec.request.packageId,
             success = updateReport.isSuccess)
    } yield spec.copy(status = UpdateStatus.Finished)

    db.run(dbIO.transactionally)
  }

  def findPendingPackageIdsFor(vin: Vehicle.Vin)
                              (implicit db: Database, ec: ExecutionContext) : DBIO[Seq[UpdateRequest]] = {
    updateSpecs
      .filter(_.vin === vin)
      .filter(_.status.inSet(List(UpdateStatus.InFlight, UpdateStatus.Pending)))
      .join(updateRequests).on(_.requestId === _.id)
      .sortBy(r => (r._1.installPos.asc, r._2.creationTime.asc))
      .map { case (sp, ur) => (sp.installPos, ur) }
      .result
      .map { _.map { case (idx, ur) => ur.copy(installPos = idx) } }
  }

  def findUpdateSpecFor(vin: Vehicle.Vin, updateRequestId: UUID)
                       (implicit ec: ExecutionContext, db: Database): DBIO[UpdateSpec] = {
    updateSpecs
      .filter(_.vin === vin)
      .filter(_.requestId === updateRequestId)
      .join(updateRequests).on(_.requestId === _.id)
      .result
      .headOption
      .flatMap {
        case Some((
          (ns, uuid, updateVin, status, installPos),
          updateRequest
          )) =>
          val spec = UpdateSpec(updateRequest, updateVin, status, Set.empty[Package])
          DBIO.successful(spec)
        case None =>
          DBIO.failed(
            UpdateSpecNotFound(s"Could not find an update request with id $updateRequestId for vin ${vin.get}")
          )
      }
  }

  /**
    * Arguments denote the number of [[UpdateRequest]] to be installed on a vehicle.
    * Each [[UpdateRequest]] may depend on a group of dependencies collected in an [[UpdateSpec]].
    *
    * @return All UpdateRequest UUIDs (for the vehicle in question) for which a pending UpdateSpec exists
    */
  private def findSpecsForSorting(vin: Vehicle.Vin, numUpdateRequests: Int)
                                 (implicit ec: ExecutionContext): DBIO[Seq[UUID]] = {

    // all UpdateRequest UUIDs (for the vehicle in question) for which a pending UpdateSpec exists
    val q = updateRequests
      .join(updateSpecs).on(_.id === _.requestId)
      .filter(_._2.vin === vin)
      .filter(_._2.status === UpdateStatus.Pending)
      .map(_._1.id)

     q.result
      .flatMap { r =>
        if(r.size > numUpdateRequests) {
          DBIO.failed(SetOrderFailed("To set install order, all updates for a vehicle need to be specified"))
        } else if(r.size != numUpdateRequests) {
          DBIO.failed(SetOrderFailed("To set install order, all updates for a vehicle need to be pending"))
        } else {
          DBIO.successful(r)
        }
      }
  }

  /**
    * Arguments denote a list of [[UpdateRequest]] to be installed on a vehicle in the order given.
    */
  def buildSetInstallOrderResponse(vin: Vehicle.Vin, order: List[UUID])
                                  (implicit db: Database, ec: ExecutionContext): Future[HttpResponse] = {
    db.run(persistInstallOrder(vin, order))
      .map(_ => HttpResponse(StatusCodes.NoContent))
      .recover {
        case SetOrderFailed(msg) =>
          HttpResponse(StatusCodes.BadRequest, headers = Nil, msg)
      }
  }

  /**
    * Arguments denote a list of [[UpdateRequest]] to be installed on a vehicle in the order given.
    */
  def persistInstallOrder(vin: Vehicle.Vin, order: List[UUID])
                         (implicit ec: ExecutionContext): DBIO[Seq[(UUID, Int)]] = {
    val prios = order.zipWithIndex.toMap

    findSpecsForSorting(vin, order.size)
      .flatMap { existingUpdReqs =>
        DBIO.sequence {
          existingUpdReqs.map { ur =>
            updateSpecs
              .filter(_.requestId === ur)
              .map(_.installPos)
              .update(prios(ur))
              .map(_ => (ur, prios(ur)))
          }
        }
      }
      .transactionally
  }
}
