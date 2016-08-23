/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.device_registry.common

object Errors {
  import akka.http.scaladsl.model.StatusCodes
  import akka.http.scaladsl.server.Directives.complete
  import akka.http.scaladsl.server.ExceptionHandler.PF
  import scala.util.control.NoStackTrace
  import org.genivi.sota.rest.{ErrorCode, ErrorRepresentation}
  import org.genivi.sota.marshalling.CirceMarshallingSupport._

  object Codes {
    val MissingDevice = ErrorCode("missing_device")
    val ConflictingDevice = ErrorCode("conflicting_device")
    val MissingSystemInfo = ErrorCode("missing_system_info")
    val SystemInfoAlreadyExists = ErrorCode("system_info_already_exists")
    val MissingGroupInfo = ErrorCode("missing_group_info")
    val GroupInfoAlreadyExists = ErrorCode("group_info_already_exists")
  }

  // TODO: Move this to raw errors or move handler up to common
  case object MissingDevice extends Throwable with NoStackTrace
  case object ConflictingDevice extends Throwable with NoStackTrace
  case object MissingSystemInfo extends Throwable with NoStackTrace
  case object SystemInfoAlreadyExists extends Throwable with NoStackTrace
  case object MissingGroupInfo extends Throwable with NoStackTrace
  case object GroupInfoAlreadyExists extends Throwable with NoStackTrace

  val onMissingDevice: PF = {
    case MissingDevice =>
      complete(StatusCodes.NotFound -> ErrorRepresentation(Codes.MissingDevice, "device doesn't exist"))
  }

  val onConflictingDevice: PF = {
    case ConflictingDevice =>
      complete(StatusCodes.Conflict ->
        ErrorRepresentation(Codes.ConflictingDevice, "deviceId or deviceName is already in use"))
  }

  val onMissingSystemInfo: PF = {
    case MissingSystemInfo =>
      complete(StatusCodes.NotFound -> ErrorRepresentation(Codes.MissingSystemInfo
                                                          , "system info doesn't exist for this uuid"))
  }

  val onSystemInfoAlreadyExists: PF = {
    case SystemInfoAlreadyExists =>
      complete(StatusCodes.Conflict ->
        ErrorRepresentation(Codes.SystemInfoAlreadyExists, "system info have been set for this uuid"))
  }

  val onMissingGroupInfo: PF = {
    case MissingGroupInfo =>
      complete(StatusCodes.NotFound -> ErrorRepresentation(Codes.MissingGroupInfo
        , "group info doesn't exist for this name"))
  }

  val onGroupInfoAlreadyExists: PF = {
    case GroupInfoAlreadyExists =>
      complete(StatusCodes.Conflict ->
        ErrorRepresentation(Codes.GroupInfoAlreadyExists, "group info have been set for this user"))
  }

}
