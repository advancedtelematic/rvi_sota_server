/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest,HttpResponse,ResponseEntity,StatusCodes}
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import org.genivi.sota.common.ClientRequest
import org.genivi.sota.device_registry.common.Errors
import scala.concurrent.{ExecutionContext, Future}

object HttpClientRequest {
  def apply[T](req: HttpRequest, cont: Future[HttpResponse] => Future[T])
              (implicit system: ActorSystem, mat: ActorMaterializer): HttpClientRequest[T]
    = new HttpClientRequest(Future.successful(req), cont)

  def apply[T](req: Future[HttpRequest], cont: Future[HttpResponse] => Future[T])
           (implicit system: ActorSystem, mat: ActorMaterializer): HttpClientRequest[T]
    = new HttpClientRequest(req, cont)
}

class HttpClientRequest[T](req: Future[HttpRequest], cont: Future[HttpResponse] => Future[T])
                (implicit system: ActorSystem, mat: ActorMaterializer) extends ClientRequest[T, HttpClientRequest] {
  override def withToken(tokenO: Option[String])(implicit ec: ExecutionContext): HttpClientRequest[T] = {
    tokenO.map { token =>
      new HttpClientRequest(req.map(_.withHeaders(Authorization(OAuth2BearerToken(token)))), cont)
    }.getOrElse(this)
  }

  override def transformResponse[S](f: Future[T] => Future[S]): HttpClientRequest[S] = {
    val newCont = (resp: Future[HttpResponse]) => f(cont(resp))

    new HttpClientRequest(req, newCont)
  }

  override def exec(implicit ec: ExecutionContext): Future[T] =
    req.flatMap { r =>
      cont(Http().singleRequest(r))
    }
}
