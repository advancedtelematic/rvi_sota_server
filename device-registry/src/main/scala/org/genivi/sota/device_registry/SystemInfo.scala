/**
  * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
  * License: MPL-2.0
  */
package org.genivi.sota.device_registry

import cats.data.State
import cats.std.list._
import cats.syntax.traverse._
import io.circe.Json
import io.circe.jawn._
import org.genivi.sota.data.Device
import org.genivi.sota.data.Uuid
import org.genivi.sota.device_registry.common.{Errors, SlickJsonHelper}
import slick.driver.MySQLDriver.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object SystemInfo extends SlickJsonHelper {
  import org.genivi.sota.db.SlickExtensions._
  import org.genivi.sota.db.Operators._

  type SystemInfoType = Json
  case class SystemInfo(uuid: Uuid, systemInfo: SystemInfoType)

  // scalastyle:off
  class SystemInfoTable(tag: Tag) extends Table[SystemInfo] (tag, "DeviceSystem") {
    def uuid = column[Uuid]("uuid")
    def systemInfo = column[Json]("system_info")

    implicit val jsonColumnType = MappedColumnType.base[Json, String](
      {json => json.noSpaces},
      {str  => parse(str).fold(_ => Json.Null, x => x)}
    )

    def * = (uuid, systemInfo).shaped <>
      ((SystemInfo.apply _).tupled, SystemInfo.unapply)

    def pk = primaryKey("uuid", uuid)
  }
  // scalastyle:on

  val systemInfos = TableQuery[SystemInfoTable]

  private def addUniqueIdsSIM(j: Json): State[Int, Json] = j.arrayOrObject(
    State.pure(j),
    _.traverseU(addUniqueIdsSIM).map(Json.fromValues),
    _.toList.traverseU {
      case ("id", value) => State { (nr: Int) =>
        (nr+1, Seq("id" -> value, "id-nr" -> Json.fromString(s"$nr")))
      }
      case (other, value) => addUniqueIdsSIM(value).map (x => Seq[(String, Json)](other -> x))
    }.map(_.flatten).map(Json.fromFields)
  )

  private def addUniqueIdsSI(j: Json): Json = addUniqueIdsSIM(j).run(0).value._2

  def exists(uuid: Uuid)
            (implicit ec: ExecutionContext): DBIO[SystemInfo] =
    systemInfos
      .filter(_.uuid === uuid)
      .result
      .headOption
      .flatMap(_.
        fold[DBIO[SystemInfo]](DBIO.failed(Errors.MissingSystemInfo))(DBIO.successful))

  def findByUuid(uuid: Uuid)(implicit ec: ExecutionContext): DBIO[SystemInfoType] =
    systemInfos
      .filter(_.uuid === uuid)
      .result
      .failIfNotSingle(Errors.MissingSystemInfo)
      .map(p => p.systemInfo)

  def create(uuid: Uuid, data: SystemInfoType)(implicit ec: ExecutionContext): DBIO[Unit] =
    (systemInfos += SystemInfo(uuid,addUniqueIdsSI(data)))
      .handleIntegrityErrors(Errors.ConflictingSystemInfo)
      .map(_ => ())

  def update(uuid: Uuid, data: SystemInfoType)(implicit ec: ExecutionContext): DBIO[Unit] =
    systemInfos.insertOrUpdate(SystemInfo(uuid,addUniqueIdsSI(data))).map(_ => ())

  def delete(uuid: Uuid)(implicit ec: ExecutionContext): DBIO[Int] =
    systemInfos.filter(_.uuid === uuid).delete

}
