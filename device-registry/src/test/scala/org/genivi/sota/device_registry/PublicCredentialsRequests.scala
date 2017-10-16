/**
  * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
  * License: MPL-2.0
  */
package org.genivi.sota.device_registry

import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import cats.syntax.show._
import eu.timepit.refined.api.Refined
import io.circe.generic.auto._
import java.util.Base64
import org.genivi.sota.data.CredentialsType.CredentialsType
import org.genivi.sota.data.{Device, DeviceT, Uuid}
import org.genivi.sota.device_registry.PublicCredentialsResource.FetchPublicCredentials
import org.genivi.sota.marshalling.CirceMarshallingSupport._

import scala.concurrent.ExecutionContext

trait PublicCredentialsRequests {self: ResourceSpec =>
  import Device._
  import StatusCodes._

  private val credentialsApi = "devices"

  private lazy val base64Decoder = Base64.getDecoder()
  private lazy val base64Encoder = Base64.getEncoder()

  def fetchPublicCredentials(device: Uuid): HttpRequest =
    Get(Resource.uri(credentialsApi, device.show, "public_credentials"))

  def fetchPublicCredentialsOk(device: Uuid): Array[Byte] = {
    fetchPublicCredentials(device) ~> route ~> check {
      status shouldBe OK
      val resp = responseAs[FetchPublicCredentials]
      base64Decoder.decode(resp.credentials)
    }
  }

  def createDeviceWithCredentials(devT: DeviceT)(implicit ec: ExecutionContext): HttpRequest =
    Put(Resource.uri(credentialsApi), devT)

  def updatePublicCredentials(device: DeviceId, creds: Array[Byte], cType: Option[CredentialsType])(implicit ec: ExecutionContext): HttpRequest = {
    val devT = DeviceT(Refined.unsafeApply(device.underlying), Some(device),
                       credentials = Some(base64Encoder.encodeToString(creds)), credentialsType = cType)
    createDeviceWithCredentials(devT)
  }

  def updatePublicCredentialsOk(device: DeviceId, creds: Array[Byte], cType: Option[CredentialsType] = None)(implicit ec: ExecutionContext): Uuid =
    updatePublicCredentials(device, creds, cType) ~> route ~> check {
      val uuid = responseAs[Uuid]
      status shouldBe OK
      uuid
    }
}
