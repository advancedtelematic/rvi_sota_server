/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.core.db

import com.advancedtelematic.libtuf.data.TufDataType.{Checksum, HashMethod, ValidChecksum}
import com.advancedtelematic.libtuf.data.TufDataType.HashMethod.HashMethod
import eu.timepit.refined.api.Refined
import org.genivi.sota.core.SotaCoreErrors
import org.genivi.sota.core.data.TargetInfo
import org.genivi.sota.data.Uuid
import slick.driver.MySQLDriver.api._

import scala.concurrent.ExecutionContext

object MultiTargetUpdates {
  import SotaCoreErrors._
  import org.genivi.sota.db.SlickExtensions._
  import com.advancedtelematic.libats.codecs.SlickRefined._

  implicit val hashMethodColumn = MappedColumnType.base[HashMethod, String](_.value.toString, HashMethod.withName)

  // scalastyle:off
  type MTURow = (Uuid, String, String, HashMethod, Refined[String, ValidChecksum], Long)
  class MultiTargetUpdates(tag: Tag) extends Table[TargetInfo](tag, "MultiTargetUpdates") {
    def id = column[Uuid]("id", O.PrimaryKey)
    def deviceIdentifier = column[String]("device_identifier")
    def targetUpdates = column[String]("target_updates")
    def hashMethod = column[HashMethod]("hash_method")
    def targetHash = column[Refined[String, ValidChecksum]]("target_hash")
    def targetSize = column[Long]("target_size")

    def * = (id, deviceIdentifier, targetUpdates, hashMethod, targetHash, targetSize).shaped <>
      ((x: MTURow) => TargetInfo(x._1, x._2, x._3, Checksum(x._4, x._5), x._6)
      ,(x: TargetInfo) => Some((x.id, x.deviceId, x.targetUpdates, x.checksum.method, x.checksum.hash, x.targetSize)))
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

  def create(row: TargetInfo)
            (implicit ec: ExecutionContext): DBIO[Unit] = {
    (multiTargets += row)
      .handleIntegrityErrors(ConflictingTargetInfo)
      .map(_ => ())
  }
}
