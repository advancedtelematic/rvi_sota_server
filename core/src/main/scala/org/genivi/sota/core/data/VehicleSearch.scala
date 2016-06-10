/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.data

import io.circe.{Decoder, Encoder}
import org.genivi.sota.core.data.UpdateStatus.UpdateStatus
import slick.driver.MySQLDriver.api._
import org.genivi.sota.core.data.VehicleStatus.VehicleStatus
import org.genivi.sota.core.db.UpdateSpecs
import org.genivi.sota.data.{Device, Vehicle}
import org.joda.time.DateTime
import org.genivi.sota.refined.SlickRefined._
import org.genivi.sota.data.Device.DeviceId
import org.genivi.sota.data.Vehicle.Vin
import eu.timepit.refined.refineV
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}


object VehicleStatus extends Enumeration {
  type VehicleStatus = Value

  val NotSeen, Error, UpToDate, Outdated = Value

  implicit val encoder : Encoder[VehicleStatus] = Encoder[String].contramap(_.toString)
  implicit val decoder : Decoder[VehicleStatus] = Decoder[String].map(VehicleStatus.withName)
}

case class VehicleUpdateStatus(vin: Vehicle.Vin, status: VehicleStatus, lastSeen: Option[DateTime])

object VehicleSearch {
  import UpdateSpecs._
  import VehicleStatus._

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  def currentVehicleStatus(lastSeen: Option[DateTime], updateStatuses: Seq[UpdateStatus]): VehicleStatus =
    if(lastSeen.isEmpty) {
      VehicleStatus.NotSeen
    } else {
      if(updateStatuses.contains(UpdateStatus.Failed)) {
        Error
      } else if(!updateStatuses.forall(_ == UpdateStatus.Finished)) {
        Outdated
      } else {
        UpToDate
      }
    }

  def fetchDeviceStatus(searchR: Future[Seq[Device]])
                       (implicit db: Database, ec: ExecutionContext): Future[Seq[VehicleUpdateStatus]] = {
    val vinLastSeens = searchR map vinWithLastSeen

    vinLastSeens flatMap { m =>
      val vinsWithDefault = m
        .map((vinDefaultStatus _).tupled)
        .map { vs => (vs.vin, vs) }
        .toMap

      val dbVins =
        VehicleSearch.dbVinStatus(m) map { dbVinStatus =>
          val dbVinsMap = dbVinStatus.map(dbV => (dbV.vin, dbV)).toMap
          vinsWithDefault ++ dbVinsMap
        } map(_.values.toSeq)

      db.run(dbVins)
    }
  }

  protected def vinDefaultStatus(vin: Vin, lastSeen: Option[DateTime]): VehicleUpdateStatus = {
    VehicleUpdateStatus(vin, currentVehicleStatus(lastSeen, Seq.empty), lastSeen)
  }

  protected def vinWithLastSeen(devices: Seq[Device]): Map[Vin, Option[DateTime]] = {
    devices
      .filter(_.deviceId.isDefined)
      .flatMap { device =>
        toVin(device.deviceId.get) match {
          case Success(vin) =>
            List((vin, device.lastSeen))
          case Failure(ex) =>
            logger.error(ex.getMessage)
            List.empty
        }
      }.toMap
  }

  protected def toVin(deviceId: DeviceId): Try[Vin] = {
    refineV[Vehicle.ValidVin](deviceId.underlying) match {
      case Right(vin) => Success(vin)
      case Left(m) => Failure(new Exception(s"Could not convert deviceId to Vin: $m"))
    }
  }

  def dbVinStatus(vins: Map[Vin, Option[DateTime]])
                 (implicit db: Database, ec: ExecutionContext): DBIO[Seq[VehicleUpdateStatus]] = {
    val updateSpecsByVin = updateSpecs.map(us => (us.vin, us.status))

    val updateStatusByVin = updateSpecs.filter(_.vin.inSet(vins.keys)).map(_.vin)
      .joinLeft(updateSpecsByVin).on(_ === _._1)
      .map { case (vin, statuses) => (vin, statuses.map(_._2)) }
      .result

    updateStatusByVin.map {
      _.groupBy(_._1)
        .values
        .map { v => (v.head._1, v.flatMap(_._2)) }
        .map { case (vin, statuses) =>
          VehicleUpdateStatus(vin, currentVehicleStatus(vins(vin), statuses), vins(vin))
        }.toSeq
    }
  }
}
