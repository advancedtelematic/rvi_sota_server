/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.core

import org.genivi.sota.common.ClientRequest
import scala.concurrent.{ExecutionContext, Future}

object FutureClientRequest {
  def apply[T](t: Future[T]): FutureClientRequest[T]
    = new FutureClientRequest(t)
}

class FutureClientRequest[T](t: Future[T]) extends ClientRequest[T, FutureClientRequest] {
  def withToken(token: Option[String])(implicit ec: ExecutionContext) = this
  def transformResponse[S](f: Future[T] => Future[S]) = FutureClientRequest(f(t))
  def exec(implicit ec: ExecutionContext): Future[T] = t
}
