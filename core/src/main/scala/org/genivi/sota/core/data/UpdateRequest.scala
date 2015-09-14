/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.data

import java.util.UUID
import cats.Foldable
import org.joda.time.{Interval, DateTime}

case class UpdateRequest( id: UUID, packageId: PackageId, creationTime: DateTime, periodOfValidity: Interval, priority: Int )

object UpdateRequest {
  import spray.json._
  import PackageId._
  import spray.json.DefaultJsonProtocol._
  import org.genivi.sota.core.DateTimeJsonProtocol._

  implicit object UpdateRequestJsonFormat extends RootJsonFormat[UpdateRequest] {
    def write(req: UpdateRequest) = {
      JsObject(
        "id" -> JsString(req.id.toString),
        "packageId" -> req.packageId.toJson,
        "creationTime" -> req.creationTime.toJson,
        "periodOfValidity" -> req.periodOfValidity.toJson,
        "priority" -> JsNumber(req.priority)
      )
    }
    def read(value: JsValue): UpdateRequest =
      value.asJsObject.getFields("id", "packageId", "creationTime", "periodOfValidity", "priority") match {
        case Seq(JsString(id), packageId, creationTime, periodOfValidity, JsNumber(priority)) =>
          UpdateRequest(
            UUID.fromString(id),
            PackageId.protocol.read(packageId),
            DateTimeJsonFormat.read(creationTime),
            DateTimeIntervalJsonFormat.read(periodOfValidity),
            priority.toInt
          )
        case _ => throw new DeserializationException("Update Request expected")
      }
  }
}

object UpdateStatus extends Enumeration {
  type UpdateStatus = Value

  val Pending, InFlight, Canceled, Failed, Finished = Value
}

import UpdateStatus._

case class UpdateSpec( request: UpdateRequest, vin: Vehicle.IdentificationNumber, status: UpdateStatus, dependencies: Set[Package] ) {
  def size : Long = dependencies.foldLeft(0L)( _ + _.size)
}

case class Download( packages: Vector[Package] )
