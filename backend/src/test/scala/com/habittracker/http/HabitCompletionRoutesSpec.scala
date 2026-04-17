package com.habittracker.http

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.habittracker.domain.AppError
import com.habittracker.domain.AppError.{ConflictError, NotFound}
import com.habittracker.http.CompletionCodecs._
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.{CreateHabitCompletionRequest, HabitCompletionResponse}
import com.habittracker.service.HabitCompletionService
import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

@RunWith(classOf[JUnitRunner])
class HabitCompletionRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  implicit val ioRuntime: IORuntime = IORuntime.global

  // ---------------------------------------------------------------------------
  // Fixed test data
  // ---------------------------------------------------------------------------

  private val habitId      = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")
  private val completionId = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff")
  private val fixedNow     = Instant.parse("2026-04-17T10:30:00Z")
  private val today        = LocalDate.of(2026, 4, 17)

  private val sampleResponse = HabitCompletionResponse(
    id = completionId,
    habitId = habitId,
    completedOn = today,
    note = Some("Felt energised"),
    createdAt = fixedNow
  )

  // ---------------------------------------------------------------------------
  // Scripted fake service
  // ---------------------------------------------------------------------------

  class FakeHabitCompletionService(
      recordResult: IO[Either[AppError, HabitCompletionResponse]] =
        IO.pure(Right(sampleResponse)),
      listResult: IO[Either[AppError, List[HabitCompletionResponse]]] =
        IO.pure(Right(List(sampleResponse))),
      deleteResult: IO[Either[AppError, Unit]] =
        IO.pure(Right(()))
  ) extends HabitCompletionService {
    override def recordCompletion(
        hId: UUID,
        req: CreateHabitCompletionRequest
    ): IO[Either[AppError, HabitCompletionResponse]] = recordResult

    override def listCompletions(
        hId: UUID,
        from: Option[LocalDate],
        to: Option[LocalDate]
    ): IO[Either[AppError, List[HabitCompletionResponse]]] = listResult

    override def deleteCompletion(
        hId: UUID,
        cId: UUID
    ): IO[Either[AppError, Unit]] = deleteResult
  }

  private def routesWith(
      service: HabitCompletionService
  ): akka.http.scaladsl.server.Route =
    new HabitCompletionRoutes(service).route

  private def jsonBody(jsonStr: String): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, jsonStr)

  // ---------------------------------------------------------------------------
  // POST /habits/{habitId}/completions
  // ---------------------------------------------------------------------------

  "POST /habits/{habitId}/completions" should {

    "return 201 and correct JSON body on happy path" in {
      val service = new FakeHabitCompletionService()
      val body = CreateHabitCompletionRequest(today, Some("Felt energised")).asJson.noSpaces

      Post(s"/habits/$habitId/completions").withEntity(jsonBody(body)) ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.Created
        val resp = decode[HabitCompletionResponse](responseAs[String])
        resp.isRight shouldBe true
        resp.toOption.get.id shouldBe completionId
        resp.toOption.get.habitId shouldBe habitId
        resp.toOption.get.completedOn shouldBe today
        resp.toOption.get.note shouldBe Some("Felt energised")
      }
    }

    "return 400 when habitId is not a valid UUID" in {
      val service = new FakeHabitCompletionService()
      val body    = CreateHabitCompletionRequest(today, None).asJson.noSpaces

      Post("/habits/not-a-uuid/completions").withEntity(jsonBody(body)) ~>
        routesWith(service) ~> check {
          status shouldBe StatusCodes.BadRequest
          val resp = decode[ErrorResponse](responseAs[String])
          resp.isRight shouldBe true
        }
    }

    "return 400 on malformed JSON body" in {
      val service = new FakeHabitCompletionService()

      Post(s"/habits/$habitId/completions").withEntity(jsonBody("{not-valid-json")) ~>
        routesWith(service) ~> check {
          status shouldBe StatusCodes.BadRequest
          val resp = decode[ErrorResponse](responseAs[String])
          resp.isRight shouldBe true
        }
    }

    "return 400 when completedOn is unparseable in JSON body" in {
      val service = new FakeHabitCompletionService()
      val body    = """{"completedOn":"not-a-date"}"""

      Post(s"/habits/$habitId/completions").withEntity(jsonBody(body)) ~>
        routesWith(service) ~> check {
          status shouldBe StatusCodes.BadRequest
        }
    }

    "return 404 when service returns NotFound" in {
      val service = new FakeHabitCompletionService(
        recordResult = IO.pure(Left(NotFound(s"Habit '$habitId' not found")))
      )
      val body = CreateHabitCompletionRequest(today, None).asJson.noSpaces

      Post(s"/habits/$habitId/completions").withEntity(jsonBody(body)) ~>
        routesWith(service) ~> check {
          status shouldBe StatusCodes.NotFound
          val resp = decode[ErrorResponse](responseAs[String])
          resp.isRight shouldBe true
        }
    }

    "return 409 when service returns ConflictError" in {
      val service = new FakeHabitCompletionService(
        recordResult = IO.pure(Left(ConflictError(s"Habit '$habitId' already has a completion for $today")))
      )
      val body = CreateHabitCompletionRequest(today, None).asJson.noSpaces

      Post(s"/habits/$habitId/completions").withEntity(jsonBody(body)) ~>
        routesWith(service) ~> check {
          status shouldBe StatusCodes.Conflict
          val resp = decode[ErrorResponse](responseAs[String])
          resp.isRight shouldBe true
        }
    }
  }

  // ---------------------------------------------------------------------------
  // GET /habits/{habitId}/completions
  // ---------------------------------------------------------------------------

  "GET /habits/{habitId}/completions" should {

    "return 200 with an array on happy path" in {
      val service = new FakeHabitCompletionService()

      Get(s"/habits/$habitId/completions") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.OK
        val resp = decode[List[HabitCompletionResponse]](responseAs[String])
        resp.isRight shouldBe true
        resp.toOption.get.length shouldBe 1
      }
    }

    "return 200 with empty array when service returns empty list" in {
      val service = new FakeHabitCompletionService(
        listResult = IO.pure(Right(List.empty))
      )

      Get(s"/habits/$habitId/completions") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "[]"
      }
    }

    "return 400 on unparseable from query param" in {
      val service = new FakeHabitCompletionService()

      Get(s"/habits/$habitId/completions?from=not-a-date") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.BadRequest
        val resp = decode[ErrorResponse](responseAs[String])
        resp.isRight shouldBe true
      }
    }

    "return 400 on unparseable to query param" in {
      val service = new FakeHabitCompletionService()

      Get(s"/habits/$habitId/completions?to=not-a-date") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.BadRequest
        val resp = decode[ErrorResponse](responseAs[String])
        resp.isRight shouldBe true
      }
    }

    "return 400 when habitId is not a valid UUID" in {
      val service = new FakeHabitCompletionService()

      Get("/habits/not-a-uuid/completions") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.BadRequest
        val resp = decode[ErrorResponse](responseAs[String])
        resp.isRight shouldBe true
      }
    }

    "return 404 when service returns NotFound" in {
      val service = new FakeHabitCompletionService(
        listResult = IO.pure(Left(NotFound(s"Habit '$habitId' not found")))
      )

      Get(s"/habits/$habitId/completions") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.NotFound
        val resp = decode[ErrorResponse](responseAs[String])
        resp.isRight shouldBe true
      }
    }
  }

  // ---------------------------------------------------------------------------
  // DELETE /habits/{habitId}/completions/{completionId}
  // ---------------------------------------------------------------------------

  "DELETE /habits/{habitId}/completions/{completionId}" should {

    "return 204 on success" in {
      val service = new FakeHabitCompletionService()

      Delete(s"/habits/$habitId/completions/$completionId") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.NoContent
      }
    }

    "return 400 when habitId is not a valid UUID" in {
      val service = new FakeHabitCompletionService()

      Delete(s"/habits/not-a-uuid/completions/$completionId") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.BadRequest
        val resp = decode[ErrorResponse](responseAs[String])
        resp.isRight shouldBe true
      }
    }

    "return 400 when completionId is not a valid UUID" in {
      val service = new FakeHabitCompletionService()

      Delete(s"/habits/$habitId/completions/not-a-uuid") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.BadRequest
        val resp = decode[ErrorResponse](responseAs[String])
        resp.isRight shouldBe true
      }
    }

    "return 404 when service returns NotFound" in {
      val service = new FakeHabitCompletionService(
        deleteResult = IO.pure(Left(NotFound(s"Completion '$completionId' not found")))
      )

      Delete(s"/habits/$habitId/completions/$completionId") ~> routesWith(service) ~> check {
        status shouldBe StatusCodes.NotFound
        val resp = decode[ErrorResponse](responseAs[String])
        resp.isRight shouldBe true
      }
    }
  }
}
