/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.core.db

import org.genivi.sota.core.SotaCoreErrors
import org.genivi.sota.core.data.{TargetInfo, TargetInfoMeta}
import org.genivi.sota.data.Uuid
import slick.driver.MySQLDriver.api._

import scala.concurrent.ExecutionContext

object MultiTargetUpdates {
  import SotaCoreErrors._
  import org.genivi.sota.db.SlickExtensions._

  // scalastyle:off
  class MultiTargetUpdates(tag: Tag) extends Table[TargetInfo](tag, "MultiTargetUpdates") {
    def id = column[Uuid]("id", O.PrimaryKey)
    def deviceIdentifier = column[String]("deviceIdentifier")
    def targetUpdates = column[String]("targetUpdates")
    def targetHash = column[String]("targetHash")
    def targetSize = column[Long]("targetSize")

    def * = (id, deviceIdentifier, targetUpdates, targetHash, targetSize).shaped <>
      (x => TargetInfo(x._1, x._2, x._3, x._4, x._5)
      ,(x: TargetInfo) => Some((x.id, x.deviceId, x.targetUpdates, x.targetHash, x.targetSize)))
  }
  // scalastyle:on

  val multiTargets = TableQuery[MultiTargetUpdates]

  def fetch(id: Uuid)
             (implicit ec: ExecutionContext): DBIO[TargetInfo] = {
    multiTargets
      .filter(_.id === id)
      .result
      .failIfNotSingle(MissingTargetInfo)
  }

  def create(row: TargetInfoMeta)
            (implicit ec: ExecutionContext): DBIO[Uuid] = {
    val id = Uuid.generate()

    (multiTargets += TargetInfo(id, row.deviceId, row.targetUpdates, row.targetHash, row.targetSize))
      .handleIntegrityErrors(ConflictingTargetInfo)
      .map(_ => id)
  }
}
