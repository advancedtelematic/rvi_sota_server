/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.data

import io.circe.{Decoder, Encoder, Json}
import org.genivi.sota.core.data.UpdateStatus.UpdateStatus
import slick.driver.MySQLDriver.api._
import org.genivi.sota.core.data.DeviceStatus.DeviceStatus
import org.genivi.sota.core.db.{UpdateSpecs, Devices}
import org.genivi.sota.core.db.Devices.DeviceTable
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.Device
import org.joda.time.DateTime
import org.genivi.sota.refined.SlickRefined._
import io.circe.syntax._
import io.circe.generic.auto._
import org.genivi.sota.marshalling.CirceMarshallingSupport._

import scala.concurrent.ExecutionContext

object DeviceStatus extends Enumeration {
  type DeviceStatus = Value

  val NotSeen, Error, UpToDate, Outdated = Value

  implicit val encoder : Encoder[DeviceStatus] = Encoder[String].contramap(_.toString)
  implicit val decoder : Decoder[DeviceStatus] = Decoder[String].map(DeviceStatus.withName)
}

case class DeviceUpdateStatus(uuid: Device.Id, status: DeviceStatus, lastSeen: Option[DateTime])

object DeviceSearch {
  import UpdateSpecs._
  import DeviceStatus._

  import org.genivi.sota.db.SlickExtensions.jodaDateTimeMapping

  def search(ns: Namespace, regex: Option[String], includeStatus: Boolean)
            (implicit db: Database, ec: ExecutionContext): DBIO[Json] = {
    val findQuery = regex match {
      case Some(r) => Devices.searchByRegex(r)
      case _ => Devices.all
    }

    if(includeStatus) {
      DeviceSearch.withStatus(findQuery) map (_.asJson)
    } else {
      val maxDeviceCount = 1000
      DeviceSearch.withoutStatus(findQuery.take(maxDeviceCount)) map (_.asJson)
    }
  }

  def currentDeviceStatus(lastSeen: Option[DateTime], updateStatuses: Seq[UpdateStatus]): DeviceStatus = {
    if(lastSeen.isEmpty)
      DeviceStatus.NotSeen
    else {
      if(updateStatuses.contains(UpdateStatus.Failed))
        Error
      else if(!updateStatuses.forall(_ == UpdateStatus.Finished))
        Outdated
      else
        UpToDate
    }
  }

  private def withoutStatus(findQuery: Query[DeviceTable, Device, Seq]): DBIO[Seq[Device]] = {
    findQuery.result
  }

  private def withStatus(deviceQuery: Query[DeviceTable, Device, Seq])
                        (implicit db: Database, ec: ExecutionContext): DBIO[Seq[DeviceUpdateStatus]] = {
    val updateSpecsByDevice = updateSpecs.map(us => (us.deviceUuid, us.status))

    val updateStatusByDevice = deviceQuery
      .joinLeft(updateSpecsByDevice).on(_.uuid === _._1)
      .map { case (device, statuses) => (device, statuses.map(_._2)) }
      .result

    updateStatusByDevice.map {
      _.groupBy { case (device, _) => device.uuid }
        .values
        .map { v => (v.head._1, v.flatMap(_._2)) }
        .map { case (device, statuses) =>
          DeviceUpdateStatus(device.uuid,
            currentDeviceStatus(device.lastSeen, statuses),
            device.lastSeen)
        }.toSeq
    }
  }
}
