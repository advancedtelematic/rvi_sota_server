package org.genivi.sota.messaging

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import cats.data.Xor
import com.typesafe.config.ConfigException.Missing
import com.typesafe.config.{Config, ConfigException}
import io.circe.{Decoder, Encoder}
import org.genivi.sota.messaging.Messages._
import org.genivi.sota.messaging.kinesis.KinesisClient
import org.genivi.sota.messaging.nats.NatsClient
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

trait MessageBusPublisher {
  lazy private val logger = LoggerFactory.getLogger(this.getClass)

  def publish[T](msg: T)(implicit ex: ExecutionContext, messageLike: MessageLike[T]): Future[Unit]

  def publishSafe[T](msg: T)(implicit ec: ExecutionContext, messageLike: MessageLike[T]): Future[Try[Unit]] = {
    publish(msg)
      .map { _ =>
        logger.info(s"published ${messageLike.streamName} - ${messageLike.id(msg)}")
        Success(())
      }
      .recover { case t =>
        logger.error(s"Could not publish $msg msg to bus", t)
        Failure(t)
    }
  }
}

object MessageBusPublisher {
  def ignore = new MessageBusPublisher {
    lazy private val _logger = LoggerFactory.getLogger(this.getClass)

    override def publish[T](msg: T)(implicit ex: ExecutionContext, messageLike: MessageLike[T]): Future[Unit] = {
      _logger.info(s"Ignoring message publish to bus")
      Future.successful(())
    }
  }

  implicit class FuturePipeToBus[T](v: Future[T]) {
    def pipeToBus[M](messageBusPublisher: MessageBusPublisher)
                    (fn: T => M)(implicit ec: ExecutionContext, messageLike: MessageLike[M]): Future[T] = {
      v.andThen {
        case Success(futureResult) => messageBusPublisher.publish(fn(futureResult))
      }
    }
  }
}

object MessageBus {
  val DEFAULT_CLIENT_BUFFER_SIZE = 1024 // number of msgs

  lazy val log = LoggerFactory.getLogger(this.getClass)

  import org.genivi.sota.marshalling.CirceInstances._

  def subscribe[T](system: ActorSystem, config: Config)
                  (implicit messageLike: MessageLike[T]): Throwable Xor Source[T, NotUsed] = {
    config.getString("messaging.mode").toLowerCase().trim match {
      case "nats" =>
        log.info("Starting messaging mode: NATS")
        log.info(s"Using subject name: ${messageLike.streamName}")
        NatsClient.source(system, config, messageLike.streamName)(messageLike.decoder)
      case "kinesis" =>
        log.info("Starting messaging mode: Kinesis")
        log.info(s"Using stream name: ${messageLike.streamName}")
        KinesisClient.source(system, config)(messageLike)
      case "local" | "test" =>
        log.info("Using local event bus")
        Xor.right(LocalMessageBus.subscribe(system)(messageLike))
      case mode =>
        Xor.left(new Missing(s"Unknown messaging mode specified ($mode)"))
    }
  }

  def publisher(system: ActorSystem, config: Config): Throwable Xor MessageBusPublisher = {
    config.getString("messaging.mode").toLowerCase().trim match {
      case "nats" =>
        log.info("Starting messaging mode: NATS")
        NatsClient.publisher(system, config)
      case "kinesis" =>
        log.info("Starting messaging mode: Kinesis")
        KinesisClient.publisher(system, config)
      case "local" | "test" =>
        log.info("Using local message bus")
        Xor.right(LocalMessageBus.publisher(system))
      case mode =>
        Xor.left(new Missing(s"Unknown messaging mode specified ($mode)"))
    }
  }
}
