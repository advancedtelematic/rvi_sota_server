package org.genivi.sota.core.daemon

import akka.Done
import org.genivi.sota.common.DeviceRegistry
import org.genivi.sota.core.{SotaCoreErrors, UpdateService}
import org.genivi.sota.core.campaigns.CampaignLauncher
import org.genivi.sota.core.db.{Campaigns, Packages}
import org.genivi.sota.messaging.MessageBusPublisher
import org.genivi.sota.messaging.Messages.{DeltaGenerationFailed, GeneratedDelta}
import slick.jdbc.MySQLProfile.api._
import org.genivi.sota.core.data.Campaign
import org.slf4j.LoggerFactory
import org.genivi.sota.messaging.Commit.Commit

import scala.concurrent.{ExecutionContext, Future}
import SotaCoreErrors._

import scala.util.control.NoStackTrace

class DeltaListener(deviceRegistry: DeviceRegistry, updateService: UpdateService,
                    messageBus: MessageBusPublisher)(implicit db: Database)  {

  private val log = LoggerFactory.getLogger(this.getClass)

  case class InvalidDeltaGeneratedMessageError(msg: String) extends Throwable(msg) with NoStackTrace

  /**
    * this method only returns a DBIO so it can be used inside a for comprehension involving slick calls
    */
  private def validateMessage(campaign: Campaign, from: Commit, to: Commit)
                             (implicit ec: ExecutionContext): DBIO[Done] = {
    import cats.implicits._

    val meta = campaign.meta

    val validDeltaFrom =
      Either.fromOption(meta.deltaFrom, "Received GeneratedDelta message for campaign without static delta")

    val validDeltaFromVersion =
      Either.cond(meta.deltaFrom.map(_.version.value).exists(_.equalsIgnoreCase(from.value)), (),
        "Received GeneratedDelta message for campaign with differing from version")

    val validPackageUuid =
      Either.fromOption(meta.packageUuid, "Received GeneratedDelta message for campaign without a target version")

    (validDeltaFrom *> validDeltaFromVersion *> validPackageUuid).map { packageUuid =>
      Packages.byUuid(packageUuid.toJava).flatMap { pkg =>
        if (pkg.id.version.value.equalsIgnoreCase(to.value)) {
          DBIO.successful(Done)
        } else {
          DBIO.failed {
            InvalidDeltaGeneratedMessageError(
              "Version in GeneratedDelta message ($to) doesn't match version in campaign (${pkg.id.version})"
            )
          }
        }
      }
    }.valueOr(err => DBIO.failed(InvalidDeltaGeneratedMessageError(err)))
  }

  def generatedDeltaAction(msg: GeneratedDelta)(implicit ec: ExecutionContext): Future[Done] = {
    log.info(s"received GeneratedDelta: $msg")

    val id = Campaign.Id(msg.id)

    val dbIO = for {
      campaign <- Campaigns.fetch(id)
      _ <- validateMessage(campaign, msg.from, msg.to)
      _ <- Campaigns.setSize(id, msg.size)
      lc <- Campaigns.fetchLaunchCampaignRequest(id)
    } yield lc

    db.run(dbIO.transactionally).flatMap { lc =>
      CampaignLauncher.launch(deviceRegistry, updateService, id, lc, messageBus)(db, ec).map(_ => Done)
    }.recover {
      case InvalidDeltaGeneratedMessageError(error) =>
        log.warn(s"Invalid DeltaGenerated message received: ${msg.id} $error")
        Done

      case MissingLaunchCampaignRequest =>
        log.warn(s"No Launch Request found for GeneratedDelta: ${msg.id}")
        Done

      case MissingCampaign =>
        log.warn("Received static delta for non existing campaign")
        Done
    }
  }

  def deltaGenerationFailedAction(msg: DeltaGenerationFailed)(implicit ec: ExecutionContext): Future[Done] = {
    log.error(s"Delta generation for campaign ${msg.id} failed with error: ${msg.error.getOrElse("")}")
    db.run(Campaigns.setAsDraft(Campaign.Id(msg.id)))
      .map(_ => Done)
      .recover {
        case MissingCampaign =>
          log.warn(s"Received static delta for non existing campaign: ${msg.id}")
          Done
      }
  }
}
