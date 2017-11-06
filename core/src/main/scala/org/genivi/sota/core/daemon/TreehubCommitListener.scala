/**
 * Copyright: Copyright (C) 2016, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.daemon

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.advancedtelematic.libats.data.DataType.{Checksum, HashMethod, Namespace, ValidChecksum}
import com.advancedtelematic.libats.data.RefinedUtils.RefineTry
import com.advancedtelematic.libtuf.data.TufDataType.{TargetName, TargetVersion, ValidHardwareIdentifier}
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.OSTREE
import com.advancedtelematic.libtuf_server.reposerver.ReposerverClient
import org.genivi.sota.core.Settings
import org.genivi.sota.messaging.MessageBusPublisher
import org.genivi.sota.messaging.Messages._
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api.Database

class TreehubCommitListener(db: Database, tufClient: ReposerverClient, bus: MessageBusPublisher)
                           (implicit system: ActorSystem,
                            mat: ActorMaterializer,
                            ec: ExecutionContext) extends Settings {

  case class ImageRequest(commit: String, refName: String, description: String, pullUri: String)

  implicit private val _config = system.settings.config
  implicit private val _db = db
  implicit private val _bus = bus

  private val log = LoggerFactory.getLogger(this.getClass)

  def action(event: TreehubCommit): Future[Unit] = {
    publishToTuf(event).recover {
      case ReposerverClient.OfflineKey =>
        log.error(s"Could not create tuf target (keys are offline) for namespace ${event.ns}")
      case ReposerverClient.UserRepoNotFound =>
        log.error(s"Could not create tuf target (repo doesn't exist) for namespace ${event.ns}")
    }
  }

  private def publishToTuf(event: TreehubCommit): Future[Unit] = {
    val targetMetadata = for {
      hardwareId <- event.refName.refineTry[ValidHardwareIdentifier]
      name = TargetName(event.refName)
      version = TargetVersion(event.commit)
      hash <- event.commit.refineTry[ValidChecksum]
    } yield (hash, Some(name), Some(version), Seq(hardwareId))

    for {
      (hash, name, version, hardwareIds) <- Future.fromTry(targetMetadata)
      filepath = s"${event.refName}-${event.commit}"
      _ <- if (filepath.length < 254) {
        tufClient.addTarget(Namespace(event.ns.get), filepath, event.uri,
                            Checksum(HashMethod.SHA256, hash), event.size, OSTREE, name, version, hardwareIds)
      } else {
        log.error(s"Can not add tuf target since filepath is too long: $filepath (will ignore)")
        Future.successful(())
      }
    } yield ()
  }
}
