/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.rvi

import java.io.File

import akka.actor._
import akka.util.ByteString
import io.circe.Encoder
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Paths, StandardOpenOption}
import java.util.UUID
import java.util.concurrent.TimeUnit

import org.genivi.sota.core.data._
import org.genivi.sota.core.db._
import akka.actor._
import akka.http.scaladsl.model.Uri
import akka.stream.ActorMaterializer
import org.apache.commons.codec.binary.Base64
import org.genivi.sota.core.data.{Package, UpdateSpec, UpdateStatus}
import org.genivi.sota.core.db.UpdateSpecs
import org.genivi.sota.core.resolver.ConnectivityClient
import org.genivi.sota.core.storage.S3PackageStore
import org.genivi.sota.core.transfer.InstalledPackagesUpdate
import org.genivi.sota.data.Device
import org.joda.time.DateTime

import scala.collection.immutable.Queue
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.math.BigDecimal.RoundingMode
import slick.driver.MySQLDriver.api.Database

import scala.util.Try


/**
 * Actor to handle events received from the RVI node.
 *
 * @param transferProtocolProps the configuration class for creating actors to handle a single device
 * @see SotaServices
 */
class UpdateController(transferProtocolProps: Props) extends Actor with ActorLogging {

  /**
   * Create actors to handle new downloads to a single device.
   * Forward messages to that actor until it finishes the download.
   * Remove the actor from the map when it is terminated.
   *
   * @param currentDownloads the map of VIN to the actor handling that device
   */
  def running( currentDownloads: Map[Device.Id, ActorRef] ) : Receive = {
    case x @ StartDownload(deviceUuid, updateId, clientServices) =>
      currentDownloads.get(deviceUuid) match {
        case None =>
          log.debug(s"New transfer to device $deviceUuid for update $updateId, count ${currentDownloads.size}")
          val actor = context.actorOf( transferProtocolProps )
          context.watch(actor)
          context.become( running( currentDownloads.updated( deviceUuid, actor ) ) )
          actor ! x
        case Some(x) =>
          log.warning(
            s"There is an active transfer for device $deviceUuid. Request to transfer update $updateId will be ignored." )
      }
    case x @ ChunksReceived(deviceUuid, _, _) =>
      currentDownloads.get(deviceUuid).foreach( _ ! x)

    case x: InstallReport =>
      log.debug(s"Install report from device ${x.device}")
      currentDownloads.get(x.device).foreach( _ ! x )

    case akka.actor.Terminated(x) =>
      log.debug(s"Transfer actor terminated, count ${currentDownloads.size}")
      context.become( running( currentDownloads.filterNot( _._2 == x ) ) )
  }

  /**
   * Entry point with initial empty map of downloads
   */
  override def receive : Receive = running( Map.empty )

}

object UpdateController {

  /**
   * Configuration class for creating the UpdateController actor.
   */
  def props( transferProtocolProps: Props ) : Props = Props( new UpdateController(transferProtocolProps) )

}

object TransferProtocolActor {

  private[TransferProtocolActor] case class Specs( values : Iterable[UpdateSpec])

  /**
   * Configuration class for creating the TransferProtocolActor actor.
   */
  def props(db: Database,
            rviClient: ConnectivityClient,
            transferActorProps: (UUID, String, Package, ClientServices) => Props) =
    Props( new TransferProtocolActor( db, rviClient, transferActorProps) )
}

object UpdateEvents {

  /**
   * Message from TransferProtocolActor when all packages are transferred to device.
   */
  final case class PackagesTransferred( update: UpdateSpec )

  /**
   * Message from TransferProtocolActor when an installation report is received from the device.
   */
  final case class InstallReportReceived( report: InstallReport )

}

/**
 * Actor to transfer packages to a single device.
 *
 * @param db the database connection
 * @param rviClient the client to the RVI node
 * @param transferActorProps the configuration class for creating the PackageTransferActor
 */
class TransferProtocolActor(db: Database, rviClient: ConnectivityClient,
                            transferActorProps: (UUID, String, Package, ClientServices) => Props)
    extends Actor with ActorLogging {
  import cats.syntax.eq._
  import cats.syntax.show._
  import context.dispatcher

  val installTimeout : FiniteDuration = FiniteDuration(
    context.system.settings.config.getDuration("rvi.transfer.installTimeout", TimeUnit.MILLISECONDS),
    TimeUnit.MILLISECONDS
  )

  def buildTransferQueue(specs: Iterable[UpdateSpec]) : Queue[(UpdateSpec, Package)] = (for {
    spec <- specs
    pkg <- spec.dependencies
  } yield (spec, pkg)).to[Queue]

  /**
   * Create actors to handle each transfer to the device.
   * Forward ChunksReceived messages from the device to the transfer actors.
   * Terminate when all updates and dependencies are successfully transferred,
   * or when the transfer is aborted because the device is not responding.
   */
  def running(services: ClientServices, updates: Set[UpdateSpec], pending: Queue[(UpdateSpec, Package)],
              inProgress: Map[ActorRef, (UpdateSpec, Package)], done: Set[Package]) : Receive = {
    case akka.actor.Terminated(ref) =>
      // TODO: Handle actors terminated on errors (e.g. file exception in PackageTransferActor)
      val (oldUpdate, oldPkg) = inProgress.get(ref).get
      log.debug(s"Package for update $oldUpdate uploaded.")
      val newInProgress = pending.headOption.map { case (update, pkg) =>
        startPackageUpload(services)(update, pkg) }
        .fold(inProgress - ref)(inProgress - ref + _)

      if (newInProgress.isEmpty) {
        log.debug( s"All packages uploaded." )
        context.setReceiveTimeout(installTimeout)
        updates.foreach { x =>
          context.system.eventStream.publish( UpdateEvents.PackagesTransferred( x ) )
        }
      } else {
        val finishedPackageTransfers = done + oldPkg
        val (finishedUpdates, unfinishedUpdates) =
          updates.span(_.dependencies.diff(finishedPackageTransfers).isEmpty)
        context.become(
          running(services, unfinishedUpdates, if (pending.isEmpty) pending else pending.tail,
                  newInProgress, finishedPackageTransfers))
      }

    case x @ ChunksReceived(_, updateId, _) =>
      inProgress.find { case (_, (spec: UpdateSpec, _)) =>
        spec.request.id == updateId
      }.foreach { case (ref: ActorRef, _) =>
        ref ! x
      }

    case r @ InstallReport(deviceUuid, update) =>
      context.system.eventStream.publish( UpdateEvents.InstallReportReceived(r) )
      log.debug(s"Install report received from $deviceUuid: ${update.update_id} installed with ${update.operation_results}")
      updates.find(_.request.id == update.update_id) match {
        case Some(spec) =>
          InstalledPackagesUpdate.reportInstall(deviceUuid, update)(dispatcher, db)
          rviClient.sendMessage(services.getpackages, io.circe.Json.Null, ttl())
          context.stop( self )
        case None => log.error(s"Update ${update.update_id} for corresponding install report does not exist!")
      }

    case ReceiveTimeout =>
      abortUpdate(services, updates)

    case UploadAborted =>
      abortUpdate(services, updates)
  }

  def abortUpdate (services: ClientServices, updates: Set[UpdateSpec]): Unit = {
      rviClient.sendMessage(services.abort, io.circe.Json.Null, ttl())
      updates.foreach(x => db.run( UpdateSpecs.setStatus(x, UpdateStatus.Canceled) ))
      context.stop(self)
  }

  def ttl() : DateTime = {
    import com.github.nscala_time.time.Implicits._
    DateTime.now + 5.minutes
  }

  def startPackageUpload(services: ClientServices)
                        (update: UpdateSpec,
                         pkg: Package): (ActorRef, (UpdateSpec, Package)) = {
    val r = update.request
    val ref = context.actorOf(transferActorProps(r.id, r.signature, pkg, services), r.id.toString)
    context.watch(ref)
    (ref, (update, pkg))
  }

  def loadSpecs(services: ClientServices) : Receive = {
    case TransferProtocolActor.Specs(values) =>
      val todo = buildTransferQueue(values)
      val workers : Map[ActorRef, (UpdateSpec, Package)] =
        todo.take(3).map { case (update, pkg) => startPackageUpload(services)(update, pkg) }.toMap
      context become running( services, values.toSet, todo.drop(3), workers, Set.empty )

    case Status.Failure(t) =>
      log.error(t, "Unable to load update specifications.")
      context stop self
  }

  /**
   * Entry point to this actor when the device initiates a download.
   */
  override def receive : Receive = {
    case StartDownload(deviceUuid, updateId, services) =>
      log.debug(s"$deviceUuid requested update $updateId")
      import akka.pattern.pipe
      db.run(UpdateSpecs.load(deviceUuid, updateId)).map(TransferProtocolActor.Specs.apply) pipeTo self
      context become loadSpecs(services)
  }

}

case class StartDownloadMessage(update_id: UUID, checksum: String, chunkscount: Int)

object StartDownloadMessage {

  import io.circe.generic.semiauto._

  implicit val encoder: Encoder[StartDownloadMessage] =
    deriveEncoder[StartDownloadMessage]

}

case class ChunksReceived(deviceUuid: Device.Id, update_id: UUID, chunks: List[Int])

case class PackageChunk(update_id: UUID, bytes: ByteString, index: Int)

object PackageChunk {

  import io.circe.generic.semiauto._

  implicit val encoder: Encoder[PackageChunk] =
    deriveEncoder[PackageChunk]

  implicit val byteStringEncoder : Encoder[ByteString] =
    Encoder[String].contramap[ByteString]( x => Base64.encodeBase64String(x.toArray) )

}

case class Finish(update_id: UUID, signature: String)

object Finish {

  import io.circe.generic.semiauto._

  implicit val encoder: Encoder[Finish] =
    deriveEncoder[Finish]

}

case object UploadAborted

/**
 * Actor to handle transferring chunks to a device.
 *
 * @param updateId Unique Id of the update.
 * @param pckg the package to transfer
 * @param services the service paths available on the device
 */
class PackageTransferActor(updateId: UUID,
                           signature: String,
                           pckg: Package,
                           services: ClientServices,
                           rviClient: ConnectivityClient)
    extends Actor with ActorLogging {

  import cats.syntax.show._
  import io.circe.generic.auto._
  import akka.pattern.pipe

  implicit val mat = ActorMaterializer()

  import context.system
  import context.dispatcher

  val chunkSize = system.settings.config.getBytes("rvi.transfer.chunkSize").intValue()
  val ackTimeout : FiniteDuration = FiniteDuration(
    system.settings.config.getDuration("rvi.transfer.ackTimeout", TimeUnit.MILLISECONDS),
    TimeUnit.MILLISECONDS
  )

  lazy val lastIndex = (BigDecimal(pckg.size) / BigDecimal(chunkSize) setScale(0, RoundingMode.CEILING)).toInt

  lazy val s3PackageStore = S3PackageStore(system.settings.config)
  val buffer = ByteBuffer.allocate( chunkSize )

  def ttl() : DateTime = {
    import com.github.nscala_time.time.Implicits._
    DateTime.now + 5.minutes
  }

  def sendChunk(channel: FileChannel, index: Int) : Unit = {
    log.debug( s"Sending chunk $index" )
    channel.position( (index - 1) * chunkSize )
    buffer.clear()
    val bytesRead = channel.read( buffer )
    buffer.flip()
    rviClient.sendMessage(services.chunk, PackageChunk(updateId, ByteString(buffer), index), ttl())
    context.setReceiveTimeout( ackTimeout )
  }

  def finish() : Unit = {
    rviClient.sendMessage(services.finish, Finish(updateId, signature), ttl())
    context stop self
  }

  override def preStart() : Unit = {
    log.debug(s"Starting transfer of the package $pckg. Chunk size: $chunkSize, chunks to transfer: $lastIndex")
    rviClient.sendMessage( services.start, StartDownloadMessage(updateId, pckg.checkSum, lastIndex), ttl() )
  }

  val maxAttempts : Int = 5

  private def openChannel(uri: URI): FileChannel = {
    FileChannel.open(Paths.get(uri), StandardOpenOption.READ)
  }

  private def isLocalFile(uri: Uri): Boolean = {
    Try(new File(new URI(uri.toString())).exists()).getOrElse(false)
  }

  /**
    * Makes sure the package URI is local, so it can be read by this actor
    * and transferred to the client
   */
  def downloadingRemotePackage(): Receive = {
    if(isLocalFile(pckg.uri)) {
      self ! pckg.uri
    } else {
      s3PackageStore
        .retrieveFile(packageUri = pckg.uri)
        .map(_.toURI).pipeTo(self)
    }

    {
      case uri: URI =>
        val channel = openChannel(uri)
        sendChunk(channel, 1)
        context.become(transferring(channel, 1, 1))

      case Status.Failure(ex) =>
        log.error(ex, "Could not download remote file")
        context.parent ! UploadAborted
        context.stop(self)

      case msg =>
        log.warning("Unexpected msg received {}", msg)
    }
  }

  // scalastyle:off
  /**
   * Send the next chunk or resend last chunk if device doesn't acknowledge with ChunksReceived.
   * Abort transfer if maxAttempts exceeded.
   */
  def transferring(channel: FileChannel, lastSentChunk: Int, attempt: Int) : Receive = {
    case ChunksReceived(_, _, indexes) =>
      log.debug(s"${pckg.id.show}. Chunk received by client: $indexes" )
      val nextIndex = indexes.sorted.sliding(2, 1).find {
        case first :: second :: Nil => second - first > 1
        case first :: Nil => true
        case _ => false
      }.map {
        case first :: _ :: Nil => first + 1
        case 1 :: Nil => 2
        case Nil => 1
        case _ => indexes.max + 1
      }.get
      log.debug(s"Next chunk index: $nextIndex")
      if( nextIndex > lastIndex ) finish()
      else {
        sendChunk(channel, nextIndex)
        context.become( transferring(channel, nextIndex, 1) )
      }

    case ReceiveTimeout if attempt == maxAttempts =>
      context.setReceiveTimeout(Duration.Undefined)
      context.parent ! UploadAborted
      context.stop( self )

    case ReceiveTimeout =>
      sendChunk(channel, lastSentChunk)
      context.become( transferring(channel, lastSentChunk, attempt + 1) )
  }
  // scalastyle:on

  /**
   * Entry point to this actor starting with first chunk.
   */
  override def receive: Receive = {
    case ChunksReceived(_, _, Nil) =>
      context.become(downloadingRemotePackage())

  }

  override def postStop() : Unit = {
  }

}

object PackageTransferActor {

  /**
   * Configuration class for creating PackageTransferActor.
   */
  def props(rviClient: ConnectivityClient)
           (updateId: UUID, signature: String, pckg: Package, services: ClientServices): Props =
    Props(new PackageTransferActor(updateId, signature, pckg, services, rviClient))

}
