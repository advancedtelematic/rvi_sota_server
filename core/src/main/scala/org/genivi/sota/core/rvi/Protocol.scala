/**
  * Copyright: Copyright (C) 2015, Jaguar Land Rover
  * License: MPL-2.0
  */
package org.genivi.sota.core.rvi

import akka.util.ByteString
import java.net.URL
// scalastyle:off
import sun.misc.BASE64Encoder
// scalastyle:on

import org.genivi.sota.core.data.Package
import spray.json.{DefaultJsonProtocol, JsonFormat, RootJsonFormat}

import scala.util.Random

object Protocol {
  case class RegisterServiceParams(networkAddress: String, service: String)
  object RegisterServiceParams extends DefaultJsonProtocol {
    import spray.json._
    implicit object registerServiceFormat extends JsonFormat[RegisterServiceParams] {
      def write(p: RegisterServiceParams): JsObject =
        JsObject(
          "network_address" -> JsString(p.networkAddress),
          "service" -> JsString(p.service)
        )

      def read(value: JsValue): RegisterServiceParams = value.asJsObject.getFields("network_address", "service") match {
        case Seq(JsString(networkAddress), JsString(service)) => RegisterServiceParams(networkAddress, service)
        case _ => deserializationError("Ack.TransferStart message expected")
      }
    }
  }

  case class RegisterServiceRequest (
    jsonrpc: String,
    params: RegisterServiceParams,
    id: Long,
    method: String
  )
  object RegisterServiceRequest extends DefaultJsonProtocol {
    implicit val format: RootJsonFormat[RegisterServiceRequest] =
      jsonFormat4(RegisterServiceRequest.apply)

    def build(networkAddress: URL, service: String): RegisterServiceRequest =
      RegisterServiceRequest(
        "2.0",
        RegisterServiceParams(networkAddress.toString + s"/rvi/$service", s"/sota/$service"),
        Random.nextLong.abs,
        "register_service"
      )
  }


  sealed trait Action
  case class Notify(retry: Int, packageName: String) extends Action
  object Notify extends DefaultJsonProtocol {
    import spray.json._

    implicit object notifyFormat extends JsonFormat[Notify] {
      def write(p: Notify): JsObject =
        JsObject(
          "retry" -> JsNumber(p.retry),
          "package" -> JsString(p.packageName)
        )

      def read(value: JsValue): Notify = value.asJsObject.getFields("retry", "package") match {
        case Seq(JsNumber(retry), JsString(packageName)) => Notify(retry.toInt, packageName)
        case _ => deserializationError("Notify param expected")
      }
    }
  }

  case class TransferStart(id: Long, totalSize: Long, packageIdentifier: String, chunkSize: Long, checksum: String) extends Action
  object TransferStart extends DefaultJsonProtocol {
    import spray.json._

    implicit object transferStartFormat extends JsonFormat[TransferStart] {
      def write(p: TransferStart): JsObject =
        JsObject(
          "id" -> JsNumber(p.id),
          "total_size" -> JsNumber(p.totalSize),
          "package" -> JsString(p.packageIdentifier),
          "chunk_size" -> JsNumber(p.chunkSize),
          "checksum" -> JsString(p.checksum)
        )

      def read(value: JsValue): TransferStart = value.asJsObject.getFields("id", "total_size", "package", "chunk_size", "checksum") match {
        case Seq(JsNumber(id), JsNumber(totalSize), JsString(packageIdentifier), JsNumber(chunkSize), JsString(checksum)) =>
          TransferStart(id.toLong, totalSize.toLong, packageIdentifier, chunkSize.toLong, checksum)
        case _ => deserializationError("TransferStart message expected")
      }
    }
  }

  case class TransferChunk(id: Long, index: Long, msg: String) extends Action
  object TransferChunk extends DefaultJsonProtocol {
    implicit val transferChunkFormat = jsonFormat3(TransferChunk.apply)
  }

  case class TransferFinish(id: Long) extends Action
  object TransferFinish extends DefaultJsonProtocol {
    implicit val transferFinishFormat = jsonFormat1(TransferFinish.apply)
  }

  case class Download(id: Long, packageIdentifier: String, destination: String) extends Action
  object Download extends DefaultJsonProtocol {
    implicit val downloadFormat = jsonFormat3(Download.apply)
  }

  object Ack {
    case class TransferStart(id: Long) extends Action
    object TransferStart extends DefaultJsonProtocol {
      import spray.json._
      implicit object ackTransferStartFormat extends JsonFormat[TransferStart] {
        def write(p: TransferStart): JsObject =
          JsObject(
            "id" -> JsNumber(p.id),
            "ack" -> JsString("start")
          )

        def read(value: JsValue): TransferStart = value.asJsObject.getFields("id", "ack") match {
          case Seq(JsNumber(id), JsString("start")) => TransferStart(id.toLong)
          case _ => deserializationError("Ack.TransferStart message expected")
        }
      }
    }

    case class TransferChunk(id: Long, index: Long) extends Action
    object TransferChunk extends DefaultJsonProtocol {
      import spray.json._
      implicit object ackTransferChunkFormat extends JsonFormat[TransferChunk] {
        def write(p: TransferChunk): JsObject =
          JsObject(
            "id" -> JsNumber(p.id),
            "index" -> JsNumber(p.index),
            "ack" -> JsString("chunk")
          )

        def read(value: JsValue): TransferChunk = value.asJsObject.getFields("id", "index", "ack") match {
          case Seq(JsNumber(id), JsNumber(index), JsString("chunk")) => TransferChunk(id.toLong, index.toLong)
          case _ => deserializationError("Ack.TransferChunk message expected")
        }
      }
    }

    case class TransferFinish(id: Long) extends Action
    object TransferFinish extends DefaultJsonProtocol {
      import spray.json._
      implicit object ackTransferFinishFormat extends JsonFormat[TransferFinish] {
        def write(p: TransferFinish): JsObject =
          JsObject(
            "id" -> JsNumber(p.id),
            "ack" -> JsString("finish")
          )

        def read(value: JsValue): TransferFinish = value.asJsObject.getFields("id", "ack") match {
          case Seq(JsNumber(id), JsString("finish")) => TransferFinish(id.toLong)
          case _ => deserializationError("Ack.TransferChunk message expected")
        }
      }
    }
  }

  case class JsonRpcParams[A <: Action](
    service_name: String,
    timeout: Long,
    parameters: Seq[A]
  )
  object JsonRpcParams extends DefaultJsonProtocol {
    implicit def jsonRpcParamsFormat[A <: Action :JsonFormat]: JsonFormat[JsonRpcParams[A]] =
      jsonFormat3(JsonRpcParams.apply[A])
  }

  case class JsonRpcRequest[A <: Action] (
    jsonrpc: String,
    params: JsonRpcParams[A],
    id: Long,
    method: String
  )
  object JsonRpcRequest extends DefaultJsonProtocol {
    implicit def format[A <: Action :JsonFormat]: RootJsonFormat[JsonRpcRequest[A]] =
      jsonFormat4(JsonRpcRequest.apply[A])

    val IdLength = 8
    val Timeout = 3
    val Retry = 5

    def actionName(a: Action): String = a match {
      case Notify(_, _) => "notify"
      case TransferStart(_, _, _, _, _) => "start"
      case TransferChunk(_, _, _) => "chunk"
      case TransferFinish(_) => "finish"
      case Download(_, _, _) => "download"
      case _ => ""
    }

    import org.genivi.sota.core.data.Vehicle

    def build[A <: Action](destination: String, action: A, id: Long = Random.nextLong.abs): JsonRpcRequest[A] =
      JsonRpcRequest(
        "2.0",
        JsonRpcParams(
          s"genivi.org$destination/${actionName(action)}",
          Timeout,
          Seq(action)
        ),
        id,
        "message"
      )

    private val base64Encoder: BASE64Encoder = new BASE64Encoder()

    def notifyPackage(vin: Vehicle.IdentificationNumber, pack: Package): JsonRpcRequest[Notify] =
      JsonRpcRequest.build(s"/vin/${vin.get}/sota", Notify(Retry, s"${pack.id.name.get}-${pack.id.version.get}"))

    def transferStart(transactionId: Long,
                      destination: String,
                      packageIdentifier: String,
                      totalSize: Long,
                      chunkSize: Long,
                      checksum: String): JsonRpcRequest[TransferStart] =
      JsonRpcRequest.build(destination, TransferStart(transactionId, totalSize, packageIdentifier, chunkSize, checksum))

    def transferChunk(transactionId: Long, destination: String, index: Long, data: Array[Byte]): JsonRpcRequest[TransferChunk] =
      JsonRpcRequest.build(destination, TransferChunk(transactionId, index, base64Encoder.encode(data)))

    def transferFinish(transactionId: Long, destination: String): JsonRpcRequest[TransferFinish] =
      JsonRpcRequest.build(destination, TransferFinish(transactionId))
  }
}
