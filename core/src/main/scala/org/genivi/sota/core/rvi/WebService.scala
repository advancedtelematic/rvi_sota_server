/**
  * Copyright: Copyright (C) 2015, Jaguar Land Rover
  * License: MPL-2.0
  */
package org.genivi.sota.core.rvi

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.FSM.Transition
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.TypedActor
import akka.actor.TypedProps
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{Directives, Route}
import akka.stream.ActorMaterializer
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object TransferSupervisor {
  case class Spawn(transactionId: Long, props: Props)
  object Ack {
    case class Start(transactionId: Long)
    case class Chunk(transactionId: Long, index: Long)
    case class Finish(transactionId: Long)
  }
}

class TransferSupervisor extends Actor with ActorLogging {
  import Transfer.Commands._
  import Transfer.Actor._
  import TransferSupervisor._

  var transfers = Map[Long, ActorRef]()

  def receive = {
    case Spawn(transactionId, props) => {
      val transfer = context.actorOf(props, name = s"transfer_$transactionId")
      transfers += transactionId -> transfer
      transfer ! Start
    }
    case Ack.Start(transactionId) if transfers.contains(transactionId) => transfers.get(transactionId).head ! Transfer.Ack.Started
    case Ack.Chunk(transactionId, index) if transfers.contains(transactionId) => transfers.get(transactionId).head ! Transfer.Ack.Chunk(index)
    case Ack.Finish(transactionId) if transfers.contains(transactionId) => transfers.get(transactionId).head ! Transfer.Ack.Finished
    case Ack.Start | Ack.Chunk | Ack.Finish => log.error("no such active transfer")
  }
}

class WebService(deviceCommunication: DeviceCommunication)(implicit system: ActorSystem, mat: ActorMaterializer, exec: ExecutionContext) extends Directives {
  val transfers: ActorRef = system.actorOf(Props[TransferSupervisor])

  import Protocol._
  import JsonRpcRequest._

  object RviRequest {
    def unapply[A <: Action](x: JsonRpcRequest[A]): Option[A] = Some(x.params.parameters.head)
  }

  import org.genivi.sota.core.data._
  import eu.timepit.refined._

  val route: Route = pathPrefix("sota") {
    path("initiate_download") {
      (post & entity(as[JsonRpcRequest[Download]])) {
        case RviRequest(Download(transactionId, packageIdentifier, destination)) =>
          complete(
            deviceCommunication.createTransfer(transactionId, destination, packageIdentifier).right.map { case transferProps =>
              transfers ! TransferSupervisor.Spawn(transactionId, transferProps)
              s"started transfer: $transactionId"
            }
          )
        }
    } ~ path("ackTransferStart") {
      (post & entity(as[JsonRpcRequest[Ack.TransferStart]])) {
        case RviRequest(Ack.TransferStart(transactionId)) => {
          transfers ! TransferSupervisor.Ack.Start(transactionId)
          complete("ok")
        }
      }
    } ~ path("ackTransferChunk") {
      (post & entity(as[JsonRpcRequest[Ack.TransferChunk]])) {
        case RviRequest(Ack.TransferChunk(transactionId, index)) => {
          transfers ! TransferSupervisor.Ack.Chunk(transactionId, index)
          complete("ok")
        }
      }
    } ~ path("ackTransferFinish") {
      (post & entity(as[JsonRpcRequest[Ack.TransferFinish]])) {
        case RviRequest(Ack.TransferFinish(transactionId)) => {
          transfers ! TransferSupervisor.Ack.Finish(transactionId)
          complete("ok")
        }
      }
    }
  }
}
