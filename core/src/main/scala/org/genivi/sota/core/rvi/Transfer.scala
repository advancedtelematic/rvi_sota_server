/**
  * Copyright: Copyright (C) 2015, Jaguar Land Rover
  * License: MPL-2.0
  */
package org.genivi.sota.core.rvi

import akka.actor.FSM
import akka.actor.FSM.Reason
import java.io.{File, FileInputStream}

import akka.actor.LoggingFSM

import scala.concurrent.duration._
import scala.io.Source

object Transfer {
  object Actor {
    sealed trait State
    case object Idle extends State
    case object Starting extends State
    case object SendingChunk extends State
    case object SentChunk extends State
    case object SendingFinish extends State
    case object SentFinish extends State
    case object Finished extends State

    case class Data(chunks: Iterator[Seq[Byte]], nextChunkIdx: Int)
  }

  sealed trait Ack
  object Ack {
    object Started extends Ack
    case class Chunk(index: Long) extends Ack
    object Finished extends Ack
  }

  object Commands {
    object Start
  }
  object Callbacks {
    case object SentStart
    case class SentChunk(idx: Long)
    case object SentFinish
  }
}

class Transfer(transactionId: Long,
               destination: String,
               file: File,
               packageIdentifier: String,
               checksum: String,
               rviNode: RviInterface) extends LoggingFSM[Transfer.Actor.State, Transfer.Actor.Data] {
  import Transfer._
  import Transfer.Actor._

  implicit val ec = context.dispatcher
  private val config = context.system.settings.config
  val chunkSize = config.getLong("packages.chunkSize")
  val ackTimeout = DurationInt(config.getInt("packages.ackTimeout")).seconds

  val fileSize: Long = file.length
  val totalChunks: Long = (fileSize / chunkSize.toDouble).ceil.toLong

  val is = new FileInputStream(file)

  val iterator = Source.fromInputStream(is).buffered.map(_.toByte).grouped(chunkSize.toInt)

  def die(reason: Option[String] = None): State = {
    is.close()
    stop(reason.map(FSM.Failure(_)).getOrElse(FSM.Normal))
  }

  startWith(Idle, Data(chunks = iterator, nextChunkIdx = 0))

  when(Idle) {
    case Event(Commands.Start, currentData) => {
      rviNode.transferStart(transactionId, destination, packageIdentifier, fileSize, chunkSize, checksum).foreach(_ => self ! Callbacks.SentStart)
      stay
    }
    case Event(Callbacks.SentStart, currentData) => goto(Starting)
  }

  // scalastyle:off
  when(Starting, stateTimeout = ackTimeout) {
    case Event(Ack.Started, currentData) => {
      rviNode.transferChunk(transactionId, destination, stateData.nextChunkIdx, getNextChunk).foreach(_ => self ! Callbacks.SentChunk(stateData.nextChunkIdx))
      goto(SendingChunk)
    }
  }
  // scalastyle:on

  // scalastyle:off
  when(SendingChunk, stateTimeout = ackTimeout) {
    case Event(Callbacks.SentChunk(i), currentData) if (i == currentData.nextChunkIdx) => goto(SentChunk)
  }
  // scalastyle:on

  // scalastyle:off
  when(SentChunk, stateTimeout = ackTimeout) {
    case Event(Ack.Chunk(index), currentData) if (currentData.nextChunkIdx == index) => {
      if (currentData.chunks.hasNext) {
        val nextChunkIdx = stateData.nextChunkIdx + 1
        rviNode.transferChunk(transactionId, destination, nextChunkIdx, getNextChunk).foreach(_ => self ! Callbacks.SentChunk(nextChunkIdx))
        goto(SendingChunk) using currentData.copy(nextChunkIdx = nextChunkIdx)
      } else {
        rviNode.transferFinish(transactionId, destination).foreach(_ => self ! Callbacks.SentFinish)
        goto(SendingFinish)
      }
    }
    case Event(Ack.Chunk(index), currentData) => {
      val errorMsg = s"received acknowledgement for chunk $index, but expected it for chunk ${currentData.nextChunkIdx}"
      die(Some(errorMsg))
    }
  }
  // scalastyle:on

  when(SendingFinish, stateTimeout = ackTimeout) {
    case Event(Callbacks.SentFinish, currentData) => goto(SentFinish)
  }

  when(SentFinish, stateTimeout = ackTimeout) {
    case Event(Ack.Finished, currentData) => die()
  }

  onTermination {
    case StopEvent(FSM.Normal, state, data)         => log.info(s"completed transfer: $transactionId")
    case StopEvent(FSM.Shutdown, state, data)       => log.info("shutdown transfer")
    case StopEvent(FSM.Failure(cause), state, data) => log.error(s"transfer $transactionId failed: $cause")
  }

  def getNextChunk: Array[Byte] = stateData.chunks.next().toArray

  whenUnhandled {
    case Event(StateTimeout, currentData) =>
      die(Some(s"timed out with state $currentData"))
    case Event(e, currentData) =>
      die(Some(s"unknown event $e with state $currentData"))
  }

  initialize()
}
