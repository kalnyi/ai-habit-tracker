package com.habittracker.http

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

@RunWith(classOf[JUnitRunner])
class DocsRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  private val docs = new DocsRoutes()

  // ---------------------------------------------------------------------------
  // GET /docs/openapi.yaml
  // ---------------------------------------------------------------------------

  "GET /docs/openapi.yaml" should {

    "return 200 with Content-Type starting with application/x-yaml" in {
      Get("/docs/openapi.yaml") ~> docs.route ~> check {
        status shouldBe StatusCodes.OK
        contentType.mediaType.toString() should startWith("application/x-yaml")
      }
    }

    "body contains openapi: and 3.0.3" in {
      Get("/docs/openapi.yaml") ~> docs.route ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("openapi:")
        body should include("3.0.3")
      }
    }
  }

  // ---------------------------------------------------------------------------
  // GET /docs/openapi.json
  // ---------------------------------------------------------------------------

  "GET /docs/openapi.json" should {

    "return 200 with Content-Type containing application/json" in {
      Get("/docs/openapi.json") ~> docs.route ~> check {
        status shouldBe StatusCodes.OK
        contentType.toString() should include("application/json")
      }
    }

    "body as parsed JSON contains expected paths and schemas" in {
      Get("/docs/openapi.json") ~> docs.route ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        val json = parse(body).toOption.get

        // paths /habits and /habits/{id} with correct HTTP methods
        val paths = json.hcursor.downField("paths")
        paths.downField("/habits").succeeded                       shouldBe true
        paths.downField("/habits/{id}").succeeded                  shouldBe true
        paths.downField("/habits").downField("post").succeeded     shouldBe true
        paths.downField("/habits").downField("get").succeeded      shouldBe true
        paths.downField("/habits/{id}").downField("get").succeeded    shouldBe true
        paths.downField("/habits/{id}").downField("put").succeeded    shouldBe true
        paths.downField("/habits/{id}").downField("delete").succeeded shouldBe true

        // frequency enum contains daily and weekly
        val frequencyEnum =
          json.hcursor
            .downField("components")
            .downField("schemas")
            .downField("Frequency")
            .downField("enum")
            .as[List[String]]
            .toOption
            .get
        frequencyEnum should contain("daily")
        frequencyEnum should contain("weekly")

        // ErrorResponse has message field
        val messageField =
          json.hcursor
            .downField("components")
            .downField("schemas")
            .downField("ErrorResponse")
            .downField("properties")
            .downField("message")
            .succeeded
        messageField shouldBe true
      }
    }
  }

  // ---------------------------------------------------------------------------
  // GET /docs
  // ---------------------------------------------------------------------------

  "GET /docs" should {

    "return 200 with Content-Type starting with text/html" in {
      Get("/docs") ~> docs.route ~> check {
        status shouldBe StatusCodes.OK
        contentType.toString() should startWith("text/html")
      }
    }
  }

  // ---------------------------------------------------------------------------
  // GET /docs/ui/swagger-ui.css
  // ---------------------------------------------------------------------------

  "GET /docs/ui/swagger-ui.css" should {

    "return 200" in {
      Get("/docs/ui/swagger-ui.css") ~> docs.route ~> check {
        status shouldBe StatusCodes.OK
      }
    }
  }
}
