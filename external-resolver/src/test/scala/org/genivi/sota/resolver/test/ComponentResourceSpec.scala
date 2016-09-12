/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.test

import akka.http.scaladsl.model.StatusCodes
import eu.timepit.refined.api.Refined
import eu.timepit.refined.refineV
import io.circe.generic.auto._
import org.genivi.sota.data.Namespaces
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.resolver.components.Component

/**
 * Specs for Component REST actions
 */
class ComponentResourceWordSpec extends ResourceWordSpec with Namespaces {

  val components = "components"

  val jobby0: Component.PartNumber = refineV[Component.ValidPartNumber]("jobby0").right.get
  val jobby1: Component.PartNumber = refineV[Component.ValidPartNumber]("jobby1").right.get
  val bobby0: Component.PartNumber = refineV[Component.ValidPartNumber]("bobby0").right.get
  val bobby1: Component.PartNumber = refineV[Component.ValidPartNumber]("bobby1").right.get

  "Component resource" should {

    "add component on PUT /components/:partNumber { description }" in {
      addComponentOK(jobby0, "nice")
    }

    "fail if part number isn't a 30 character or shorter alpha numeric string" in {
      addComponent(Refined.unsafeApply(""), "bad") ~> route ~> check {
        status shouldBe StatusCodes.NotFound
      }
      addComponent(Refined.unsafeApply("0123456789012345678901234567890123456789"), "bad") ~> route ~> check {
        status shouldBe StatusCodes.BadRequest
      }
      addComponent(Refined.unsafeApply("a???"), "bad") ~> route ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "list all available components on GET /components" in {

      addComponentOK(jobby1, "nice")
      addComponentOK(bobby0, "nice")
      addComponentOK(bobby1, "nice")

      Get(Resource.uri(components)) ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[Seq[Component]] shouldBe List(Component(defaultNs, bobby0, "nice"),
                                                 Component(defaultNs, bobby1, "nice"),
                                                 Component(defaultNs, jobby0, "nice"),
                                                 Component(defaultNs, jobby1, "nice"))
      }
    }

    "list all components matching a regex on GET /components?regex=:regex" in {

      Get(Resource.uri(components) + "?regex=^j.*$") ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[Seq[Component]] shouldBe List(Component(defaultNs, jobby0, "nice"),
                                                 Component(defaultNs, jobby1, "nice"))
      }
      Get(Resource.uri(components) + "?regex=^.*0$") ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[Seq[Component]] shouldBe List(Component(defaultNs, bobby0, "nice"),
                                                 Component(defaultNs, jobby0, "nice"))
      }
    }

    "fail on trying to list components matching a malformated regex" in {
      Get(Resource.uri(components) + "?regex=(a") ~> route ~> check {
        status shouldBe StatusCodes.BadRequest

      }
    }

    "delete components on DELETE /components/:partNumber" in {
      Delete(Resource.uri(components, "jobby1")) ~> route ~> check {
        status shouldBe StatusCodes.OK
      }
      Get(Resource.uri(components)) ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[Seq[Component]] shouldBe List(Component(defaultNs, bobby0, "nice"),
                                                 Component(defaultNs, bobby1, "nice"),
                                                 Component(defaultNs, jobby0, "nice"))
      }
    }

    "Components request should reject when not authorized" in {
      val cmpn = Component(defaultNs, bobby0, "nice")
      isRejected{ listComponents }
      isRejected{ addComponent(cmpn) }
      isRejected{ deleteComponent(cmpn) }
      isRejected{ updateComponent(cmpn) }
    }
  }
}
