/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.data

import eu.timepit.refined.api.{Refined, Validate}
import java.util.UUID
import org.genivi.sota.data.Namespace._
import org.joda.time.DateTime

/*
 * The notion of a device has a UUID, this is
 * shared between the core and resolver.
 */
case class Device(namespace: Namespace,
                  uuid: Device.Id,
                  lastSeen: Option[DateTime] = None) {

  override def toString: String = s"Device($uuid, $lastSeen)"
}

object Device {

  def tupled: ((Namespace, Id, Option[DateTime])) => Device = { case (ns, uuid, lastSeen) =>
    Device(ns, uuid, lastSeen)
  }

  def fromId: ((Namespace, Id)) => Device = { case (ns, uuid) =>
    Device(ns, uuid, None)
  }

  def toId: Device => Option[(Namespace, Id)] = { d => Some((d.namespace, d.uuid)) }

  case class ValidVin()

  /**
    * A valid VIN, see ISO 3779 and ISO 3780, must be 17 letters or
    * digits long and not contain 'I', 'O' or 'Q'. We enforce this at the
    * type level by refining the string type with the following
    * predicate.
    *
    * @see [[https://github.com/fthomas/refined]]
    */
  implicit val validVin : Validate.Plain[String, ValidVin] = Validate.fromPredicate(
    vin => vin.length == 17
        && vin.forall(c => (c.isUpper  || c.isDigit)
        && (c.isLetter || c.isDigit)
        && !List('I', 'O', 'Q').contains(c)),
    vin => s"($vin must be 17 letters or digits long and not contain 'I', 'O', or 'Q')",
    ValidVin()
  )

  case class ValidDeviceId()

  implicit val validDeviceId : Validate.Plain[String, ValidDeviceId] = Validate.fromPredicate(
    // TODO: define valid deviceIds
    id => id.length <= 200 && id.forall(c => (c.isLetter || c.isDigit)),
    id => s"($id must be alphanumeric)",
    ValidDeviceId()
  )

  type Id = UUID
  // type DeviceId = String Refined ValidDeviceId
  type Vin = String Refined ValidVin

  implicit val VinOrdering: Ordering[Vin] = new Ordering[Vin] {
    override def compare(v1: Vin, v2: Vin): Int = v1.get compare v2.get
  }
}
