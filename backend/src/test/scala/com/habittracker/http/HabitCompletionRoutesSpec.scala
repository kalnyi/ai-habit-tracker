package com.habittracker.http

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.habittracker.domain.AppError
import com.habittracker.domain.AppError.{ConflictError, NotFound}
import com.habittracker.http.CompletionCodecs._
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.{CreateHabitCompletionRequest, HabitCompletionResponse}
import com.habittracker.service.HabitCompletionService
import io.circe.parser.decode
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

@RunWith(classOf[JUnitRunner])
class HabitCompletionRoutesSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  // ---------------------------------------------------------------------------
  // Fixed test data
  // ---------------------------------------------------------------------------

  private val habitId      = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")
  private val completionId = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff")
  private val fixedNow     = Instant.parse("2026-04-17T10:30:00Z")
  private val today        = LocalDate.of(2026, 4, 17)

  private val sampleResponse = HabitCompletionResponse(
    id          = completionId,
    habitId     = habitId,
    completedOn = today,
    note        = Some("Felt energised"),
    createdAt   = fixedNow
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
        userId: Long,
        hId: UUID,
        req: CreateHabitCompletionRequest
    ): IO[Either[AppError, HabitCompletionResponse]] = recordResult

    override def listCompletions(
        userId: Long,
        hId: UUID,
        from: Option[LocalDate],
        to: Option[LocalDate]
    ): IO[Either[AppError, List[HabitCompletionResponse]]] = listResult

    override def deleteCompletion(
        userId: Long,
        hId: UUID,
        cId: UUID
    ): IO[Either[AppError, Unit]] = deleteResult
  }

  private def appWith(service: HabitCompletionService): HttpApp[IO] =
    new HabitCompletionRoutes(service).routes.orNotFound

  // ---------------------------------------------------------------------------
  // POST /users/1/habits/{habitId}/completions
  // ---------------------------------------------------------------------------

  "POST /users/1/habits/{habitId}/completions" should {

    "return 201 and correct JSON body on happy path" in {
      val service = new FakeHabitCompletionService()
      val body    = CreateHabitCompletionRequest(today, Some("Felt energised"))
      val req     = Request[IO](Method.POST, Uri.unsafeFromString(s"/users/1/habits/$habitId/completions"))
        .withEntity(body)
      appWith(service).run(req).flatMap { resp =>
        resp.bodyText.compile.string.map { b =>
          resp.status shouldBe Status.Created
          val parsed = decode[HabitCompletionResponse](b)
          parsed.isRight shouldBe true
          parsed.toOption.get.id shouldBe completionId
          parsed.toOption.get.habitId shouldBe habitId
          parsed.toOption.get.completedOn shouldBe today
          parsed.toOption.get.note shouldBe Some("Felt energised")
        }
      }
    }

    "return 400 when habitId is not a valid UUID" in {
      val service = new FakeHabitCompletionService()
      val body    = CreateHabitCompletionRequest(today, None)
      val req     = Request[IO](Method.POST, uri"/users/1/habits/not-a-uuid/completions").withEntity(body)
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.BadRequest
      }
    }

    "return 400 on malformed JSON body" in {
      val service = new FakeHabitCompletionService()
      val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/users/1/habits/$habitId/completions"))
        .withEntity("{not-valid-json")
        .withContentType(headers.`Content-Type`(MediaType.application.json))
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.BadRequest
      }
    }

    "return 400 when completedOn is unparseable in JSON body" in {
      val service = new FakeHabitCompletionService()
      val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/users/1/habits/$habitId/completions"))
        .withEntity("""{"completedOn":"not-a-date"}""")
        .withContentType(headers.`Content-Type`(MediaType.application.json))
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.BadRequest
      }
    }

    "return 404 when service returns NotFound" in {
      val service = new FakeHabitCompletionService(
        recordResult = IO.pure(Left(NotFound(s"Habit '$habitId' not found")))
      )
      val body = CreateHabitCompletionRequest(today, None)
      val req  = Request[IO](Method.POST, Uri.unsafeFromString(s"/users/1/habits/$habitId/completions"))
        .withEntity(body)
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.NotFound
      }
    }

    "return 409 when service returns ConflictError" in {
      val service = new FakeHabitCompletionService(
        recordResult = IO.pure(Left(ConflictError(s"Already completed for $today")))
      )
      val body = CreateHabitCompletionRequest(today, None)
      val req  = Request[IO](Method.POST, Uri.unsafeFromString(s"/users/1/habits/$habitId/completions"))
        .withEntity(body)
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.Conflict
      }
    }
  }

  // ---------------------------------------------------------------------------
  // GET /users/1/habits/{habitId}/completions
  // ---------------------------------------------------------------------------

  "GET /users/1/habits/{habitId}/completions" should {

    "return 200 with an array on happy path" in {
      val service = new FakeHabitCompletionService()
      val req     = Request[IO](Method.GET, Uri.unsafeFromString(s"/users/1/habits/$habitId/completions"))
      appWith(service).run(req).flatMap { resp =>
        resp.bodyText.compile.string.map { body =>
          resp.status shouldBe Status.Ok
          val parsed = decode[List[HabitCompletionResponse]](body)
          parsed.isRight shouldBe true
          parsed.toOption.get.length shouldBe 1
        }
      }
    }

    "return 200 with empty array when service returns empty list" in {
      val service = new FakeHabitCompletionService(listResult = IO.pure(Right(List.empty)))
      val req     = Request[IO](Method.GET, Uri.unsafeFromString(s"/users/1/habits/$habitId/completions"))
      appWith(service).run(req).flatMap { resp =>
        resp.bodyText.compile.string.map { body =>
          resp.status shouldBe Status.Ok
          body shouldBe "[]"
        }
      }
    }

    "return 400 on unparseable from query param" in {
      val service = new FakeHabitCompletionService()
      val req     = Request[IO](Method.GET,
        Uri.unsafeFromString(s"/users/1/habits/$habitId/completions?from=not-a-date"))
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.BadRequest
      }
    }

    "return 400 on unparseable to query param" in {
      val service = new FakeHabitCompletionService()
      val req     = Request[IO](Method.GET,
        Uri.unsafeFromString(s"/users/1/habits/$habitId/completions?to=not-a-date"))
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.BadRequest
      }
    }

    "return 400 when habitId is not a valid UUID" in {
      val service = new FakeHabitCompletionService()
      val req     = Request[IO](Method.GET, uri"/users/1/habits/not-a-uuid/completions")
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.BadRequest
      }
    }

    "return 404 when service returns NotFound" in {
      val service = new FakeHabitCompletionService(
        listResult = IO.pure(Left(NotFound(s"Habit '$habitId' not found")))
      )
      val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/users/1/habits/$habitId/completions"))
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.NotFound
      }
    }
  }

  // ---------------------------------------------------------------------------
  // DELETE /users/1/habits/{habitId}/completions/{completionId}
  // ---------------------------------------------------------------------------

  "DELETE /users/1/habits/{habitId}/completions/{completionId}" should {

    "return 204 on success" in {
      val service = new FakeHabitCompletionService()
      val req     = Request[IO](Method.DELETE,
        Uri.unsafeFromString(s"/users/1/habits/$habitId/completions/$completionId"))
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.NoContent
      }
    }

    "return 400 when habitId is not a valid UUID" in {
      val service = new FakeHabitCompletionService()
      val req     = Request[IO](Method.DELETE,
        Uri.unsafeFromString(s"/users/1/habits/not-a-uuid/completions/$completionId"))
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.BadRequest
      }
    }

    "return 400 when completionId is not a valid UUID" in {
      val service = new FakeHabitCompletionService()
      val req     = Request[IO](Method.DELETE,
        Uri.unsafeFromString(s"/users/1/habits/$habitId/completions/not-a-uuid"))
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.BadRequest
      }
    }

    "return 404 when service returns NotFound" in {
      val service = new FakeHabitCompletionService(
        deleteResult = IO.pure(Left(NotFound(s"Completion '$completionId' not found")))
      )
      val req = Request[IO](Method.DELETE,
        Uri.unsafeFromString(s"/users/1/habits/$habitId/completions/$completionId"))
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.NotFound
      }
    }
  }
}
