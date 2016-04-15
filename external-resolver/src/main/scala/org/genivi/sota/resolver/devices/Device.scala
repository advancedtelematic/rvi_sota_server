/*
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.devices

import eu.timepit.refined.api.{Refined, Validate}
import org.genivi.sota.data.Namespace._
import org.joda.time.DateTime


/*
 * The notion of a device has a UUID, this is
 * shared between the core and resolver.
 */
case class Device(namespace: Namespace,
                  id: Device.DeviceId) {

  override def toString: String = s"Device($namespace, $id)"
}

object Device {

  type DeviceId = String Refined ValidDeviceId

  def fromId: ((Namespace, DeviceId)) => Device = { case (ns, id) => Device(ns, id) }
  def toId: Device => Option[(Namespace, DeviceId)] = { d => Some((d.namespace, d.id)) }

  case class ValidDeviceId()

  implicit val validDeviceId: Validate.Plain[String, ValidDeviceId] = Validate.fromPredicate(
    // TODO: define valid deviceIds
    id => id.length <= 200 && id.forall(c => (c.isLetter || c.isDigit || c == '|' || c == '.')),
    id => s"($id ist not valid)",
    ValidDeviceId()
  )

  implicit val DeviceIdOrdering: Ordering[DeviceId] = new Ordering[DeviceId] {
    override def compare(d1: DeviceId, d2: DeviceId): Int = d1.get compare d2.get
  }
}

