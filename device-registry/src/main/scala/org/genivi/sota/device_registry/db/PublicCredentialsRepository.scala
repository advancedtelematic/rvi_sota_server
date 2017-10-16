/**
  * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
  * License: MPL-2.0
  */
package org.genivi.sota.device_registry.db

import org.genivi.sota.data.CredentialsType.CredentialsType
import org.genivi.sota.data.Uuid
import org.genivi.sota.db.SlickAnyVal._
import org.genivi.sota.db.SlickExtensions._
import org.genivi.sota.device_registry.db.SlickMappings._
import org.genivi.sota.device_registry.common.Errors

import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

object PublicCredentialsRepository {
  case class DevicePublicCredentials(device: Uuid, typeCredentials: CredentialsType, credentials: Array[Byte])

  class PublicCredentialsTable(tag: Tag) extends Table[DevicePublicCredentials] (tag, "DevicePublicCredentials") {
    def device = column[Uuid]("device_uuid")
    def typeCredentials = column[CredentialsType]("type_credentials")
    def publicCredentials = column[Array[Byte]]("public_credentials")

    def * = (device, typeCredentials, publicCredentials).shaped <>
      ((DevicePublicCredentials.apply _).tupled, DevicePublicCredentials.unapply)

    def pk = primaryKey("device_uuid", device)
  }

  val allPublicCredentials = TableQuery[PublicCredentialsTable]

  def findByUuid(uuid: Uuid)(implicit ec: ExecutionContext): DBIO[DevicePublicCredentials] = {
    allPublicCredentials.filter(_.device === uuid)
      .result
      .failIfNotSingle(Errors.MissingDevicePublicCredentials)
  }

  def update(uuid: Uuid, cType: CredentialsType, creds: Array[Byte])(implicit ec: ExecutionContext): DBIO[Unit] = {
    (allPublicCredentials.insertOrUpdate(DevicePublicCredentials(uuid, cType, creds)))
      .map(_ => ())
  }

}
