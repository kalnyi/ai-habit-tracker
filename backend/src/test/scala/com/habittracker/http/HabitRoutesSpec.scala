package com.habittracker.http

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.habittracker.domain.AppError
import com.habittracker.domain.AppError.{NotFound, ValidationError}
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.{CreateHabitRequest, HabitResponse, UpdateHabitRequest}
import com.habittracker.service.HabitService
import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

@RunWith(classOf[JUnitRunner])
class HabitRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  implicit val ioRuntime: IORuntime = IORuntime.global

  // ---------------------------------------------------------------------------
  // Scripted fake service
  // ---------------------------------------------------------------------------

  private val fixedId  = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff")
  private val fixedNow = Instant.parse("2026-04-16T10:30:00Z")

  private val sampleResponse = HabitResponse(
    id = fixedId,
    name = "Read 20 pages",
    description = Some("Non-fiction"),
    frequency = "daily",
    createdAt = fixedNow,
    updatedAt = fixedNow
  )

  class FakeHabitService(
      createResult: IO[Either[AppError, HabitResponse]] = IO.pure(Right(sampleResponse)),
      listResult: IO[Either[AppError, List[HabitResponse]]] = IO.pure(Right(List(sampleResponse))),
      getResult: IO[Either[AppError, HabitResponse]] = IO.pure(Right(sampleResponse)),
      updateResult: IO[Either[AppError, HabitResponse]] = IO.pure(Right(sampleResponse)),
      deleteResult: IO[Either[AppError, Unit]] = IO.pure(Right(()))
  ) extends HabitService {
    override def createHabit(req: CreateHabitRequest): IO[Either[AppError, HabitResponse]] = createResult
    override def listHabits(): IO[Either[AppError, List[HabitResponse]]] = listResult
    override def getHabit(id: UUID): IO[Either[AppError, HabitResponse]] = getResult
    override def updateHabit(id: UUID, req: UpdateHabitRequest): IO[Either[AppError, HabitResponse]] = updateResult
    override def deleteHabit(id: UUID): IO[Either[AppError, Unit]] = deleteResult
  }

  private def routesWith(service: HabitService): akka.http.scaladsl.server.Route =
    new HabitRoutes(service).route

  private def jsonBody(jsonStr: String): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, jsonStr)

  // ---------------------------------------------------------------------------
  // POST /habits
  // ---------------------------------------------------------------------------

  "POST /habits" should {

    "return 201 and the created habit on valid input" in {
      val service = new FakeHabitService()
      val body    = CreateHabitRequest("Read 20 pages", Some("Non-fiction"), "daily").asJson.noSpaces

      Post("/habits").withEntity(jsonBody(body)) ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.Created
        val resp = decode[HabitResponse](responseAs[String])
        resp.isRight shouldBe true
        resp.toOption.get.name shouldBe "Read 20 pages"
      }
    }

    "return 400 when service returns ValidationError" in {
      val service = new FakeHabitService(
        createResult = IO.pure(Left(ValidationError("name must not be blank")))
      )
      val body = """{"name":"","description":null,"frequency":"daily"}"""

      Post("/habits").withEntity(jsonBody(body)) ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.BadRequest
        val resp = decode[ErrorResponse](responseAs[String])
        resp.isRight shouldBe true
      }
    }

    "return 400 when JSON body is malformed" in {
      val service = new FakeHabitService()

      Post("/habits").withEntity(jsonBody("{not-valid-json")) ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.BadRequest
        val resp = decode[ErrorResponse](responseAs[String])
        resp.isRight shouldBe true
      }
    }
  }

  // ---------------------------------------------------------------------------
  // GET /habits
  // ---------------------------------------------------------------------------

  "GET /habits" should {

    "return 200 and an array of habits" in {
      val service = new FakeHabitService()

      Get("/habits") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.OK
        val resp = decode[List[HabitResponse]](responseAs[String])
        resp.isRight shouldBe true
        resp.toOption.get.length shouldBe 1
      }
    }

    "return 200 and an empty array when no habits exist" in {
      val service = new FakeHabitService(listResult = IO.pure(Right(List.empty)))

      Get("/habits") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "[]"
      }
    }
  }

  // ---------------------------------------------------------------------------
  // GET /habits/{id}
  // ---------------------------------------------------------------------------

  "GET /habits/{id}" should {

    "return 200 and the habit when found" in {
      val service = new FakeHabitService()

      Get(s"/habits/$fixedId") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.OK
        val resp = decode[HabitResponse](responseAs[String])
        resp.isRight shouldBe true
      }
    }

    "return 404 when habit is not found" in {
      val service = new FakeHabitService(
        getResult = IO.pure(Left(NotFound("Habit not found")))
      )

      Get(s"/habits/${UUID.randomUUID()}") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.NotFound
        val resp = decode[ErrorResponse](responseAs[String])
        resp.isRight shouldBe true
      }
    }

    "return 400 when id is not a valid UUID" in {
      val service = new FakeHabitService()

      Get("/habits/not-a-uuid") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should not be empty
      }
    }
  }

  // ---------------------------------------------------------------------------
  // PUT /habits/{id}
  // ---------------------------------------------------------------------------

  "PUT /habits/{id}" should {

    "return 200 and the updated habit on success" in {
      val service = new FakeHabitService()
      val body    = UpdateHabitRequest("Updated", None, "weekly").asJson.noSpaces

      Put(s"/habits/$fixedId").withEntity(jsonBody(body)) ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.OK
        val resp = decode[HabitResponse](responseAs[String])
        resp.isRight shouldBe true
      }
    }

    "return 404 when habit is not found" in {
      val service = new FakeHabitService(
        updateResult = IO.pure(Left(NotFound("Habit not found")))
      )
      val body = UpdateHabitRequest("Updated", None, "weekly").asJson.noSpaces

      Put(s"/habits/${UUID.randomUUID()}").withEntity(jsonBody(body)) ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
  }

  // ---------------------------------------------------------------------------
  // DELETE /habits/{id}
  // ---------------------------------------------------------------------------

  "DELETE /habits/{id}" should {

    "return 204 on successful soft-delete" in {
      val service = new FakeHabitService()

      Delete(s"/habits/$fixedId") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.NoContent
      }
    }

    "return 404 when habit is not found" in {
      val service = new FakeHabitService(
        deleteResult = IO.pure(Left(NotFound("Habit not found")))
      )

      Delete(s"/habits/${UUID.randomUUID()}") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "return 400 when id is not a valid UUID" in {
      val service = new FakeHabitService()

      Delete("/habits/not-a-uuid") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }
}
