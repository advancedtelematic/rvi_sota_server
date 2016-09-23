/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.http

import akka.http.scaladsl.model.headers.{HttpChallenges,OAuth2BearerToken}
import akka.http.scaladsl.server.{AuthenticationFailedRejection,Directive1,Directives}
import com.typesafe.config.ConfigFactory

object AuthToken {
  import Directives.{extractCredentials,provide,reject}
  import AuthenticationFailedRejection.CredentialsMissing

  lazy val config   = ConfigFactory.load()
  lazy val protocol = config.getString("auth.protocol")

  def allowAll: Directive1[Option[String]] = provide(None)

  def fromConfig(): Directive1[Option[String]] = {
    protocol match {
      case "none" =>
        allowAll
      case _ =>
        extractCredentials flatMap { creds =>
          creds match {
            case Some(OAuth2BearerToken(token)) => provide(Some(token))
            case _ => reject(AuthenticationFailedRejection(CredentialsMissing, HttpChallenges.oAuth2("")))
          }
        }
    }
  }
}
