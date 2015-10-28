/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.webserver.controllers

import javax.inject.Inject

import jp.t2v.lab.play2.auth.{AuthElement, LoginLogout}
import org.genivi.webserver.Authentication.{AccountManager, Role}
import org.slf4j.LoggerFactory
import play.api.Play.current
import play.api._
import play.api.data.Forms._
import play.api.data._
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.JsValue
import play.api.libs.json.Json._
import play.api.libs.ws._
import play.api.mvc._
import views.html

import scala.concurrent.{ExecutionContext, Future}

class Application @Inject() (ws: WSClient, val messagesApi: MessagesApi, val accountManager: AccountManager)
  extends Controller with LoginLogout with AuthConfigImpl with I18nSupport with AuthElement {

  val auditLogger = LoggerFactory.getLogger("audit")
  implicit val context = play.api.libs.concurrent.Execution.Implicits.defaultContext

  val coreApiUri = Play.current.configuration.getString("core.api.uri").get
  val resolverApiUri = Play.current.configuration.getString("resolver.api.uri").get

  def apiByPath(path: String) = path.split("/").toList match {
    case "packages" :: _ => coreApiUri
    case "updates" :: _ => coreApiUri
    case "vehicles" :: vin :: "queued" :: _ => coreApiUri
    case "vehicles" :: vin :: "history" :: _ => coreApiUri
    case _ => resolverApiUri
  }

  def proxyTo(apiUri: String, req: Request[RawBuffer]) : Future[Result] = {
    def toWsHeaders(hdrs: Headers) = hdrs.toMap.map {
      case(name, value) => name -> value.mkString }

    WS.url(apiUri + req.path)
      .withFollowRedirects(false)
      .withMethod(req.method)
      .withHeaders(toWsHeaders(req.headers).toSeq :_*)
      .withQueryString(req.queryString.mapValues(_.head).toSeq :_*)
      .withBody(req.body.asBytes().get)
      .execute
      .map { resp => Result(
        header = ResponseHeader(
          status = resp.status,
          headers = resp.allHeaders.mapValues(x => x.head)),
        body = Enumerator(resp.bodyAsBytes))
      }
  }

  def apiProxy(path: String) = AsyncStack(parse.raw, AuthorityKey -> Role.USER) { implicit req =>
    { // Mitigation for C04: Log transactions to and from SOTA Server
      auditLogger.info(s"Request: $req from user ${loggedIn.name}")
    }
    proxyTo(apiByPath(path), req)
  }

  def apiProxyBroadcast(path: String) = AsyncStack(parse.raw, AuthorityKey -> Role.USER) { implicit req =>
    { // Mitigation for C04: Log transactions to and from SOTA Server
      auditLogger.info(s"Request: $req from user ${loggedIn.name}")
    }

    // Must PUT "vehicles" on both core and resolver
    // TODO: Retry until both responses are success
    for {
      respCore <- proxyTo(coreApiUri, req)
      respResult <- proxyTo(resolverApiUri, req)
    } yield respCore
  }

  def index = StackAction(AuthorityKey -> Role.USER) { implicit req =>
    Ok(views.html.main())
  }

  def resolveUser(id: Id)(implicit ctx: ExecutionContext): Future[Option[User]] = {
    Future.successful(accountManager.findById(id))
  }

  val loginForm = Form {
    mapping("email" -> email, "password" -> nonEmptyText)(accountManager.authenticate)(_.map(u => (u.email, "")))
      .verifying("Invalid email or password", result => result.isDefined)
  }

  def login = Action { request =>
    Ok(html.login(loginForm))
  }

  def logout = Action.async{ implicit request =>
    gotoLogoutSucceeded
  }

  def authenticate = Action.async { implicit request =>
    loginForm.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(views.html.login(formWithErrors))),
      user => gotoLoginSucceeded(user.get.email)
    )
  }
}
