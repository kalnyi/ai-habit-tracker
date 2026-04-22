package com.habittracker.http

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import io.circe.parser.parse
import org.http4s._
import org.http4s.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

@RunWith(classOf[JUnitRunner])
class DocsRoutesSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private val docsApp: HttpApp[IO] = new DocsRoutes().routes.orNotFound

  // ---------------------------------------------------------------------------
  // GET /docs/openapi.yaml
  // ---------------------------------------------------------------------------

  "GET /docs/openapi.yaml" should {

    "return 200 with Content-Type starting with application/x-yaml" in {
      val req = Request[IO](Method.GET, uri"/docs/openapi.yaml")
      docsApp.run(req).asserting { resp =>
        resp.status shouldBe Status.Ok
        val ct = resp.headers.get[headers.`Content-Type`].map(_.mediaType.toString())
        ct.exists(_.contains("x-yaml")) shouldBe true
      }
    }

    "body contains openapi: and 3.0.3" in {
      val req = Request[IO](Method.GET, uri"/docs/openapi.yaml")
      docsApp.run(req).flatMap { resp =>
        resp.as[String].map { body =>
          resp.status shouldBe Status.Ok
          body should include("openapi:")
          body should include("3.0.3")
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // GET /docs/openapi.json
  // ---------------------------------------------------------------------------

  "GET /docs/openapi.json" should {

    "return 200 with Content-Type containing application/json" in {
      val req = Request[IO](Method.GET, uri"/docs/openapi.json")
      docsApp.run(req).asserting { resp =>
        resp.status shouldBe Status.Ok
        val ct = resp.headers.get[headers.`Content-Type`].map(_.toString())
        ct.exists(_.contains("application/json")) shouldBe true
      }
    }

    "body as parsed JSON contains expected paths and schemas" in {
      val req = Request[IO](Method.GET, uri"/docs/openapi.json")
      docsApp.run(req).flatMap { resp =>
        resp.as[String].map { body =>
          resp.status shouldBe Status.Ok
          val json = parse(body).toOption.get

          val paths = json.hcursor.downField("paths")
          paths.downField("/users/{userId}/habits").succeeded                              shouldBe true
          paths.downField("/users/{userId}/habits/{habitId}").succeeded                   shouldBe true
          paths.downField("/users/{userId}/habits").downField("post").succeeded            shouldBe true
          paths.downField("/users/{userId}/habits").downField("get").succeeded             shouldBe true
          paths.downField("/users/{userId}/habits/{habitId}").downField("get").succeeded   shouldBe true
          paths.downField("/users/{userId}/habits/{habitId}").downField("put").succeeded   shouldBe true
          paths.downField("/users/{userId}/habits/{habitId}").downField("delete").succeeded shouldBe true

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
  }

  // ---------------------------------------------------------------------------
  // GET /docs
  // ---------------------------------------------------------------------------

  "GET /docs" should {

    "return 200 with Content-Type starting with text/html" in {
      val req = Request[IO](Method.GET, uri"/docs")
      docsApp.run(req).asserting { resp =>
        resp.status shouldBe Status.Ok
        val ct = resp.headers.get[headers.`Content-Type`].map(_.toString())
        ct.exists(_.contains("text/html")) shouldBe true
      }
    }
  }

  // ---------------------------------------------------------------------------
  // GET /docs/ui/swagger-ui.css
  // ---------------------------------------------------------------------------

  "GET /docs/ui/swagger-ui.css" should {

    "return 200" in {
      val req = Request[IO](Method.GET, uri"/docs/ui/swagger-ui.css")
      docsApp.run(req).asserting { resp =>
        resp.status shouldBe Status.Ok
      }
    }
  }
}
