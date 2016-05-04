/*
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.data

import eu.timepit.refined.api.{Refined, Validate}
import java.util.UUID
import org.genivi.sota.data.Namespace._
import org.genivi.sota.db.SlickEnum
import org.genivi.sota.marshalling.CirceEnum
import org.joda.time.DateTime


/*
 * The notion of a device has a UUID, this is
 * shared between the core and resolver.
 */
case class Device(namespace: Namespace,
                  uuid: Device.Id,
                  deviceId: Device.DeviceId,
                  deviceType: Device.DeviceType,
                  lastSeen: Option[DateTime] = None) {

  override def toString: String = s"Device($uuid, $lastSeen)"
}

object Device {

  type Id = UUID
  type DeviceId = String Refined ValidDeviceId
  type DeviceType = DeviceType.DeviceType

  object DeviceType extends CirceEnum with SlickEnum {
    type DeviceType = Value
    val Other, Vehicle = Value
  }

  def tupled: ((Namespace, Id, DeviceId, DeviceType, Option[DateTime])) => Device =
    { case (ns, uuid, deviceId, deviceType, lastSeen) =>
        Device(ns, uuid, deviceId, deviceType, lastSeen)
    }

  def fromId: ((Namespace, Id, DeviceId, DeviceType)) => Device = { case (ns, uuid, deviceId, deviceType) =>
    Device(ns, uuid, deviceId, deviceType, None)
  }

  def toId: Device => Option[(Namespace, Id)] = { d => Some((d.namespace, d.uuid)) }

  case class ValidDeviceId()

  implicit val validDeviceId : Validate.Plain[String, ValidDeviceId] = Validate.fromPredicate(
    // TODO: define valid deviceIds
    id => id.length <= 200 && id.forall(c => (c.isLetter || c.isDigit)),
    id => s"($id must be alphanumeric)",
    ValidDeviceId()
  )

  implicit val DeviceIdOrdering: Ordering[DeviceId] = new Ordering[DeviceId] {
    override def compare(d1: DeviceId, d2: DeviceId): Int = d1.get compare d2.get
  }

}
