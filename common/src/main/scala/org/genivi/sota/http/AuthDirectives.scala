/*
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */

package org.genivi.sota.http

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

import akka.http.scaladsl.server.{Directives, _}
import akka.http.scaladsl.server.Directives._
import cats.data.Xor
import com.advancedtelematic.akka.http.jwt.InvalidScopeRejection
import com.advancedtelematic.jwa.HS256
import com.advancedtelematic.jws.{CompactSerialization, Jws, KeyLookup}
import com.typesafe.config.{ConfigException, ConfigFactory}
import org.apache.commons.codec.binary.Base64
import org.slf4j.LoggerFactory

object AuthDirectives {
  import com.advancedtelematic.akka.http.jwt.JwtDirectives._

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  type AuthScope = String

  def fromConfig(): (AuthedNamespaceScope, AuthScope, Boolean) => Directive0 = (authedNs, scope, readonly) => {
    if (authedNs.hasScope(scope, readonly)) pass
    else reject(InvalidScopeRejection(scope), AuthorizationFailedRejection)
  }
}
