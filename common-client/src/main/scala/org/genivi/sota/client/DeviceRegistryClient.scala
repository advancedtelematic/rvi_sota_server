/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.client

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import cats.syntax.show._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import io.circe.Json
import io.circe.generic.auto._
import java.time.Instant
import org.genivi.sota.common.DeviceRegistry
import org.genivi.sota.data.{Device, DeviceT, Namespace, Uuid}
import org.genivi.sota.device_registry.common.Errors
import org.genivi.sota.marshalling.CirceMarshallingSupport

import scala.concurrent.{ExecutionContext, Future}

class DeviceRegistryClient(baseUri: Uri, devicesUri: Uri)
                          (implicit system: ActorSystem, mat: ActorMaterializer)
    extends DeviceRegistry {

  import CirceMarshallingSupport._
  import Device._
  import HttpMethods._
  import StatusCodes._

  private val log = Logging(system, "org.genivi.sota.deviceRegistryClient")

  type Request[T] = HttpClientRequest[T]

  override def searchDevice(ns: Namespace, re: String Refined Regex)
                           (implicit ec: ExecutionContext): Request[Seq[Device]] =
    execHttp[Seq[Device]](HttpRequest(uri = baseUri.withPath(devicesUri.path)
      .withQuery(Query("regex" -> re.get, "namespace" -> ns.get))))
      .recover { case t =>
        log.error(t, "Could not contact device registry")
        Seq.empty[Device]
      }

  override def createDevice(device: DeviceT)
                           (implicit ec: ExecutionContext): Request[Uuid] = execHttp {
    for {
      entity <- Marshal(device).to[MessageEntity]
    } yield HttpRequest(method = POST, uri = baseUri.withPath(devicesUri.path), entity = entity)
  }

  override def fetchDevice(uuid: Uuid)
                          (implicit ec: ExecutionContext): Request[Device] =
    execHttp[Device](HttpRequest(uri = baseUri.withPath(devicesUri.path / uuid.show)))

  override def fetchByDeviceId(ns: Namespace, deviceId: DeviceId)
                              (implicit ec: ExecutionContext): Request[Device] =
    execHttp[Seq[Device]](HttpRequest(uri = baseUri.withPath(devicesUri.path)
      .withQuery(Query("namespace" -> ns.get, "deviceId" -> deviceId.show))))
      .flatMap {
        case d +: _ => FastFuture.successful(d)
        case _ => FastFuture.failed(Errors.MissingDevice)
      }

  override def updateDevice(uuid: Uuid, device: DeviceT)
                           (implicit ec: ExecutionContext): Request [Unit] = execHttp {
    for {
      entity <- Marshal(device).to[MessageEntity]
    } yield (HttpRequest(method = PUT, uri = baseUri.withPath(devicesUri.path / uuid.show), entity = entity))
  }

  override def deleteDevice(uuid: Uuid)
                  (implicit ec: ExecutionContext): Request[Unit] =
    execHttp[Unit](HttpRequest(method = DELETE, uri = baseUri.withPath(devicesUri.path / uuid.show)))

  override def updateLastSeen(uuid: Uuid, seenAt: Instant = Instant.now)
                             (implicit ec: ExecutionContext): Request[Unit] =
    execHttp[Unit](HttpRequest(method = POST, uri = baseUri.withPath(devicesUri.path / uuid.show / "ping")))

  override def updateSystemInfo(uuid: Uuid, json: Json)
                              (implicit ec: ExecutionContext): Request[Unit] =
    execHttp[Unit](HttpRequest(method = PUT,
                               uri = baseUri.withPath(devicesUri.path / uuid.show / "system_info"),
                               entity = HttpEntity(ContentTypes.`application/json`, json.noSpaces)))

  override def getSystemInfo(uuid: Uuid)
                            (implicit ec: ExecutionContext): Request[Json] =
    execHttp[Json](HttpRequest(method = GET,
                               uri = baseUri.withPath(devicesUri.path / uuid.show / "system_info")))

  private def execHttp[T](httpRequest: HttpRequest)
                      (implicit unmarshaller: Unmarshaller[ResponseEntity, T],
                       ec: ExecutionContext): Request[T] = execHttp(Future.successful(httpRequest))

  private def execHttp[T](httpRequest: Future[HttpRequest])
                      (implicit unmarshaller: Unmarshaller[ResponseEntity, T],
                       ec: ExecutionContext): Request[T] = {

    def cont(resp: Future[HttpResponse]): Future[T] = resp flatMap { response =>
      response.status match {
        case Conflict => FastFuture.failed(Errors.ConflictingDeviceId)
        case NotFound => FastFuture.failed(Errors.MissingDevice)
        case other if other.isSuccess() => unmarshaller(response.entity)
        case err => FastFuture.failed(new Exception(err.toString))
      }
    }

    HttpClientRequest(httpRequest, cont _)
  }
}
