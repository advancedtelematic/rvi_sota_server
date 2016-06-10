/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.data.client

import java.util.UUID

import org.genivi.sota.core.data.UpdateRequest
import org.genivi.sota.datatype.Namespace.Namespace
import org.genivi.sota.data.PackageId
import org.joda.time.{DateTime, Interval}
import scala.language.implicitConversions

case class PendingUpdateRequest(requestId: UUID, packageId: PackageId, installPos: Int, createdAt: DateTime)

object PendingUpdateRequest {
  implicit def toResponseEncoder: ResponseEncoder[PendingUpdateRequest, UpdateRequest] =
    ResponseEncoder { u =>
      PendingUpdateRequest(u.id, u.packageId, u.installPos, u.creationTime)
    }
}

case class ClientUpdateRequest(id: UUID,
                         packageId: PackageId,
                         creationTime: DateTime = DateTime.now,
                         periodOfValidity: Interval,
                         priority: Int,
                         signature: String,
                         description: Option[String],
                         requestConfirmation: Boolean)

object ClientUpdateRequest {
  implicit def fromRequestDecoder: RequestDecoder[ClientUpdateRequest, UpdateRequest, Namespace] =
    RequestDecoder { (req: ClientUpdateRequest, namespace: Namespace) =>
        UpdateRequest(req.id,
          namespace,
          req.packageId,
          req.creationTime,
          req.periodOfValidity,
          req.priority, req.signature, req.description, req.requestConfirmation)
    }
}
