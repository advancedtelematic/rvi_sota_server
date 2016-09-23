/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
// TODO Move this to client pkg
package org.genivi.sota.common

import scala.concurrent.{ExecutionContext, Future}

trait ClientRequest[Resp, E[T] <: ClientRequest[T, E]] { self: E[Resp] =>
  def withToken(token: Option[String])(implicit ec: ExecutionContext): E[Resp]

  def transformResponse[S](f: Future[Resp] => Future[S]): E[S]

  def flatMap[S](f: Resp => Future[S])
             (implicit ec: ExecutionContext): E[S]
    = transformResponse(_.flatMap(f))

  def recover(pf: PartialFunction[Throwable, Resp])
             (implicit ec: ExecutionContext): E[Resp]
    = transformResponse(_.recover(pf))

  def exec(implicit ec: ExecutionContext): Future[Resp]
}
