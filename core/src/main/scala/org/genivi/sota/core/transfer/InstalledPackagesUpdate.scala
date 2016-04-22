/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.transfer


import java.util.UUID

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import io.circe.syntax._
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.{PackageId, Device}
import org.genivi.sota.core.data._
import org.genivi.sota.core.db.{InstallHistories, OperationResults, UpdateRequests, UpdateSpecs}
import org.genivi.sota.db.SlickExtensions
import slick.dbio.DBIO
import slick.driver.MySQLDriver.api._
import org.genivi.sota.core.db.UpdateSpecs._
import org.genivi.sota.core.resolver.ExternalResolverClient
import org.genivi.sota.core.rvi.UpdateReport

import scala.concurrent.{ExecutionContext, Future}
import org.genivi.sota.refined.SlickRefined._


object InstalledPackagesUpdate {
  import SlickExtensions._

  case class UpdateSpecNotFound(msg: String) extends Exception(msg)

  def update(deviceUuid: Device.Id, packageIds: List[PackageId], resolverClient: ExternalResolverClient): Future[Unit] = {
    val ids = packageIds.asJson
    resolverClient.setInstalledPackages(deviceUuid, ids)
  }

  def buildReportInstallResponse(deviceUuid: Device.Id, updateReport: UpdateReport)
                                (implicit ec: ExecutionContext, db: Database): Future[HttpResponse] = {
    reportInstall(deviceUuid, updateReport) map { _ =>
      HttpResponse(StatusCodes.NoContent)
    } recover { case t: UpdateSpecNotFound =>
      HttpResponse(StatusCodes.NotFound, entity = t.getMessage)
    }
  }

  def reportInstall(deviceUuid: Device.Id, updateReport: UpdateReport)
                   (implicit ec: ExecutionContext, db: Database): Future[UpdateSpec] = {
    val writeResultsIO = updateReport
      .operation_results
      .map(r => org.genivi.sota.core.data.OperationResult(r.id, updateReport.update_id, r.result_code, r.result_text))
      .map(r => OperationResults.persist(r))

    val dbIO = for {
      spec <- findUpdateSpecFor(deviceUuid, updateReport.update_id)
      _ <- DBIO.sequence(writeResultsIO)
      _ <- UpdateSpecs.setStatus(spec, UpdateStatus.Finished)
      _ <- InstallHistories.log(spec.namespace, deviceUuid, spec.request.id, spec.request.packageId, success = true)
    } yield spec.copy(status = UpdateStatus.Finished)

    db.run(dbIO)
  }

  def findPendingPackageIdsFor(ns: Namespace, deviceUuid: Device.Id)
                              (implicit db: Database, ec: ExecutionContext) : DBIO[Seq[UpdateRequest]] = {
    updateSpecs
      .filter(r => r.namespace === ns && r.deviceUuid === deviceUuid)
      .filter(_.status.inSet(List(UpdateStatus.InFlight, UpdateStatus.Pending)))
      .join(updateRequests).on(_.requestId === _.id)
      .sortBy(_._2.creationTime.asc)
      .map(_._2)
      .result
  }

  def findUpdateSpecFor(deviceUuid: Device.Id, updateRequestId: UUID)
                       (implicit ec: ExecutionContext, db: Database): DBIO[UpdateSpec] = {
    updateSpecs
      .filter(_.deviceUuid === deviceUuid)
      .filter(_.requestId === updateRequestId)
      .join(updateRequests).on(_.requestId === _.id)
      .result
      .headOption
      .flatMap {
        case Some(((ns: Namespace, uuid: UUID, updateUuid: Device.Id, status: UpdateStatus.UpdateStatus),
                   updateRequest: UpdateRequest)) =>
          val spec = UpdateSpec(ns, updateRequest, updateUuid, status, Set.empty[Package])
          DBIO.successful(spec)
        case None =>
          DBIO.failed(
            UpdateSpecNotFound(s"Could not find an update request with id $updateRequestId for device ${deviceUuid.toString}")
          )
      }
  }
}
