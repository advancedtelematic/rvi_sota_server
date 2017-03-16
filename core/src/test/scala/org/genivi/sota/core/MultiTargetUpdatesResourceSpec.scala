/*
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */

package org.genivi.sota.core

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.implicits._
import io.circe.generic.auto._
import org.genivi.sota.DefaultPatience
import org.genivi.sota.core.data.{TargetInfo, TargetInfoMeta}
import org.genivi.sota.data.Uuid
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FunSuite, ShouldMatchers}

class MultiTargetUpdatesResourceSpec extends FunSuite
    with ScalatestRouteTest
    with DatabaseSpec
    with ShouldMatchers
    with ScalaFutures
    with LongRequestTimeout
    with DefaultPatience
    with Generators
{
  val service = new MultiTargetUpdatesResource()(db, system)

  object Resource {
    def uri(pathSuffixes: String*): Uri = {
      val BasePath = Path / "multi_target_updates"
      Uri.Empty.withPath(pathSuffixes.foldLeft(BasePath)(_/_))
    }
  }

  def createTargetInfoOk(targetInfo: TargetInfoMeta): Uuid =
    Post(Resource.uri(), targetInfo) ~> service.route ~> check {
      status shouldBe StatusCodes.Created
      responseAs[Uuid]
    }

  def fetchTargetInfoOk(id: Uuid): TargetInfo =
    Get(Resource.uri(id.show)) ~> service.route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[TargetInfo]
    }

  test("create target info") {
    val targetInfo = TargetInfoGen.sample.get
    createTargetInfoOk(targetInfo)
  }

  test("fetch target info") {
    val targetInfo = TargetInfoGen.sample.get
    val id = createTargetInfoOk(targetInfo)
    val response = fetchTargetInfoOk(id)
    response.id shouldBe id
    response.deviceId shouldBe targetInfo.deviceId
    response.targetUpdates shouldBe targetInfo.targetUpdates
    response.targetHash shouldBe targetInfo.targetHash
    response.targetSize shouldBe targetInfo.targetSize
  }
}
