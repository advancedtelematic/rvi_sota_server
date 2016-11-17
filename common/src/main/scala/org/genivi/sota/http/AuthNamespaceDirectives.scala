package org.genivi.sota.http

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.{AuthorizationFailedRejection, Directive0, Directive1, Directives, Rejection}
import akka.http.scaladsl.server.Directives._
import cats.data.Xor
import com.advancedtelematic.akka.http.jwt.InvalidScopeRejection
import com.advancedtelematic.jws.CompactSerialization
import com.advancedtelematic.jwt.{JsonWebToken, Scope, Subject}
import io.circe.parser._
import org.genivi.sota.data.Namespace._
import eu.timepit.refined.refineV
import io.circe.Decoder
import org.genivi.sota.data.Namespace

case class AuthedNamespaceScope(namespace: Namespace, scope: Scope, owned: Boolean = false) {
  type ScopeItem = String

  def hasScope(sc: ScopeItem, readonly: Boolean = false) : Boolean =
    owned || scope.underlying.contains(sc) ||
    (readonly && scope.underlying.contains(sc + ".readonly"))

  def oauthScope(scope: ScopeItem, readonly: Boolean = false): Directive0 = {
    if (hasScope(scope, readonly)) pass
    else reject(InvalidScopeRejection(scope), AuthorizationFailedRejection)
  }
}

object AuthedNamespaceScope {
  import scala.language.implicitConversions
  implicit def toNamespace(ns: AuthedNamespaceScope) = ns.namespace

  val namespacePrefix = "namespace."

  def apply(ns: Namespace) : AuthedNamespaceScope = AuthedNamespaceScope(ns, Scope(Set.empty), true)

  def apply(token: IdToken) : AuthedNamespaceScope = {
    AuthedNamespaceScope(Namespace(token.sub.underlying))
  }

  def apply(token: JsonWebToken) : AuthedNamespaceScope = {
    val nsSet = token.scope.underlying.collect {
      case x if x.startsWith(namespacePrefix) => x.substring(namespacePrefix.length)
    }
    if (nsSet.size == 1) {
      AuthedNamespaceScope(Namespace(nsSet.toVector(0)), token.scope, false)
    } else {
      AuthedNamespaceScope(Namespace(token.subject.underlying), token.scope, true)
    }
  }
}

/**
  * Type class defining an extraction of namespace information from a token of type `T`
  * @tparam T type of a token
  */
trait NsFromToken[T] {
  def toNamespaceScope(token: T): AuthedNamespaceScope
}

object NsFromToken {

  implicit val NsFromIdToken = new NsFromToken[IdToken] {
    override def toNamespaceScope(token: IdToken) = AuthedNamespaceScope(token)
  }

  implicit val NsFromJwt = new NsFromToken[JsonWebToken] {
    override def toNamespaceScope(token: JsonWebToken) = AuthedNamespaceScope(token)
  }

  def parseToken[T: NsFromToken](serializedToken: String)
    (implicit decoder: Decoder[T]): Xor[String, T] =
    for {
      serialized <- CompactSerialization.parse(serializedToken)
      token      <- decode[T](serialized.encodedPayload.stringData()).leftMap(_.getMessage)
    } yield token

}

/**
  * Identity token
  * @param sub Subject claim
  */
final case class IdToken(sub: Subject)

object IdToken {

  import io.circe.generic.semiauto._
  import org.genivi.sota.marshalling.CirceInstances._
  implicit val DecoderInstance: Decoder[IdToken] = deriveDecoder[IdToken]

}

object AuthNamespaceDirectives {
  import Directives._

  private[this] def badNamespaceRejection(msg: String): Rejection = AuthorizationFailedRejection

  def authNamespace[T](ns0: Option[Namespace])
                   (implicit nsFromToken: NsFromToken[T], decoder: Decoder[T]): Directive1[AuthedNamespaceScope] =
    extractCredentials flatMap { creds =>
      val maybeNamespace = creds match {
        case Some(OAuth2BearerToken(serializedToken)) =>
          NsFromToken.parseToken[T](serializedToken).flatMap{ token =>
            val authedNs = nsFromToken.toNamespaceScope(token)
            ns0 match {
              case Some(ns) if ns == authedNs.namespace => Xor.right(authedNs)
              case Some(ns) if authedNs.hasScope(AuthedNamespaceScope.namespacePrefix + ns) =>
                Xor.right(AuthedNamespaceScope(ns, authedNs.scope, false))
              case Some(ns) => Xor.Left("The oauth token does not accept the given namespace")
              case None => Xor.right(authedNs)
            }
          }

        case _ => Xor.Left("No oauth token provided to extract namespace")
    }

    maybeNamespace match {
      case Xor.Right(t) => provide(t)
      case Xor.Left(msg) =>
        extractLog flatMap { l =>
          l.info(s"Could not extract namespace: $msg")
          reject(badNamespaceRejection(msg))
        }
    }
  }
}
