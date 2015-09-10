/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.rest

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server._


object Handlers {

  import org.genivi.sota.CirceSupport._

  def rejectionHandler : RejectionHandler = RejectionHandler.newBuilder().handle {
    case ValidationRejection(msg, None) =>
      complete( StatusCodes.BadRequest -> ErrorRepresentation(ErrorCodes.InvalidEntity, msg) )
  }.handle{
    case MalformedRequestContentRejection(_, Some(RefinementError(_, msg))) =>
      complete(StatusCodes.BadRequest -> ErrorRepresentation(ErrorCodes.InvalidEntity, msg))
  }.result().withFallback(RejectionHandler.default)

  def exceptionHandler: ExceptionHandler = ExceptionHandler {
    case err: java.sql.SQLIntegrityConstraintViolationException if err.getErrorCode == 1062 =>
      complete(StatusCodes.Conflict ->
        ErrorRepresentation(ErrorCodes.DuplicateEntry, "Entry already exists"))
  }
}
