package com.habittracker.http

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.habittracker.domain.AppError
import com.habittracker.domain.AppError.{NotFound, ValidationError}
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.{CreateHabitRequest, HabitResponse, UpdateHabitRequest}
import com.habittracker.service.HabitService
import io.circe.parser.decode
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.util.UUID

@RunWith(classOf[JUnitRunner])
class HabitRoutesSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private val fixedId  = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff")
  private val fixedNow = Instant.parse("2026-04-16T10:30:00Z")

  private val sampleResponse = HabitResponse(
    id          = fixedId,
    name        = "Read 20 pages",
    description = Some("Non-fiction"),
    frequency   = "daily",
    createdAt   = fixedNow,
    updatedAt   = fixedNow
  )

  // ---------------------------------------------------------------------------
  // Scripted fake service
  // ---------------------------------------------------------------------------

  class FakeHabitService(
      createResult: IO[Either[AppError, HabitResponse]] = IO.pure(Right(sampleResponse)),
      listResult: IO[Either[AppError, List[HabitResponse]]] = IO.pure(Right(List(sampleResponse))),
      getResult: IO[Either[AppError, HabitResponse]] = IO.pure(Right(sampleResponse)),
      updateResult: IO[Either[AppError, HabitResponse]] = IO.pure(Right(sampleResponse)),
      deleteResult: IO[Either[AppError, Unit]] = IO.pure(Right(()))
  ) extends HabitService {
    override def createHabit(userId: Long, req: CreateHabitRequest): IO[Either[AppError, HabitResponse]] = createResult
    override def listHabits(userId: Long): IO[Either[AppError, List[HabitResponse]]]                     = listResult
    override def getHabit(userId: Long, id: UUID): IO[Either[AppError, HabitResponse]]                   = getResult
    override def updateHabit(userId: Long, id: UUID, req: UpdateHabitRequest): IO[Either[AppError, HabitResponse]] = updateResult
    override def deleteHabit(userId: Long, id: UUID): IO[Either[AppError, Unit]]                         = deleteResult
  }

  private def appWith(service: HabitService): HttpApp[IO] =
    new HabitRoutes(service).routes.orNotFound

  // ---------------------------------------------------------------------------
  // POST /users/1/habits
  // ---------------------------------------------------------------------------

  "POST /users/1/habits" should {

    "return 201 and the created habit on valid input" in {
      val service = new FakeHabitService()
      val body    = CreateHabitRequest("Read 20 pages", Some("Non-fiction"), "daily")
      val req     = Request[IO](Method.POST, uri"/users/1/habits").withEntity(body)
      appWith(service).run(req).flatMap { resp =>
        resp.bodyText.compile.string.map { body =>
          resp.status shouldBe Status.Created
          val parsed = decode[HabitResponse](body)
          parsed.isRight shouldBe true
          parsed.toOption.get.name shouldBe "Read 20 pages"
        }
      }
    }

    "return 400 when service returns ValidationError" in {
      val service = new FakeHabitService(
        createResult = IO.pure(Left(ValidationError("name must not be blank")))
      )
      val body = CreateHabitRequest("", None, "daily")
      val req  = Request[IO](Method.POST, uri"/users/1/habits").withEntity(body)
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.BadRequest
      }
    }

    "return 400 when JSON body is malformed" in {
      val service = new FakeHabitService()
      val req = Request[IO](Method.POST, uri"/users/1/habits")
        .withEntity("{not-valid-json")
        .withContentType(headers.`Content-Type`(MediaType.application.json))
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.BadRequest
      }
    }
  }

  // ---------------------------------------------------------------------------
  // GET /users/1/habits
  // ---------------------------------------------------------------------------

  "GET /users/1/habits" should {

    "return 200 and an array of habits" in {
      val service = new FakeHabitService()
      val req     = Request[IO](Method.GET, uri"/users/1/habits")
      appWith(service).run(req).flatMap { resp =>
        resp.bodyText.compile.string.map { body =>
          resp.status shouldBe Status.Ok
          val parsed = decode[List[HabitResponse]](body)
          parsed.isRight shouldBe true
          parsed.toOption.get.length shouldBe 1
        }
      }
    }

    "return 200 and an empty array when no habits exist" in {
      val service = new FakeHabitService(listResult = IO.pure(Right(List.empty)))
      val req     = Request[IO](Method.GET, uri"/users/1/habits")
      appWith(service).run(req).flatMap { resp =>
        resp.bodyText.compile.string.map { body =>
          resp.status shouldBe Status.Ok
          body shouldBe "[]"
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // GET /users/1/habits/{id}
  // ---------------------------------------------------------------------------

  "GET /users/1/habits/{id}" should {

    "return 200 and the habit when found" in {
      val service = new FakeHabitService()
      val req     = Request[IO](Method.GET, Uri.unsafeFromString(s"/users/1/habits/$fixedId"))
      appWith(service).run(req).flatMap { resp =>
        resp.bodyText.compile.string.map { body =>
          resp.status shouldBe Status.Ok
          decode[HabitResponse](body).isRight shouldBe true
        }
      }
    }

    "return 404 when habit is not found" in {
      val service = new FakeHabitService(getResult = IO.pure(Left(NotFound("Habit not found"))))
      val req     = Request[IO](Method.GET, Uri.unsafeFromString(s"/users/1/habits/${UUID.randomUUID()}"))
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.NotFound
      }
    }

    "return 400 when id is not a valid UUID" in {
      val service = new FakeHabitService()
      val req     = Request[IO](Method.GET, uri"/users/1/habits/not-a-uuid")
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.BadRequest
      }
    }
  }

  // ---------------------------------------------------------------------------
  // PUT /users/1/habits/{id}
  // ---------------------------------------------------------------------------

  "PUT /users/1/habits/{id}" should {

    "return 200 and the updated habit on success" in {
      val service = new FakeHabitService()
      val body    = UpdateHabitRequest("Updated", None, "weekly")
      val req     = Request[IO](Method.PUT, Uri.unsafeFromString(s"/users/1/habits/$fixedId")).withEntity(body)
      appWith(service).run(req).flatMap { resp =>
        resp.bodyText.compile.string.map { body =>
          resp.status shouldBe Status.Ok
          decode[HabitResponse](body).isRight shouldBe true
        }
      }
    }

    "return 404 when habit is not found" in {
      val service = new FakeHabitService(updateResult = IO.pure(Left(NotFound("Habit not found"))))
      val body    = UpdateHabitRequest("Updated", None, "weekly")
      val req     = Request[IO](Method.PUT, Uri.unsafeFromString(s"/users/1/habits/${UUID.randomUUID()}")).withEntity(body)
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.NotFound
      }
    }
  }

  // ---------------------------------------------------------------------------
  // DELETE /users/1/habits/{id}
  // ---------------------------------------------------------------------------

  "DELETE /users/1/habits/{id}" should {

    "return 204 on successful soft-delete" in {
      val service = new FakeHabitService()
      val req     = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/users/1/habits/$fixedId"))
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.NoContent
      }
    }

    "return 404 when habit is not found" in {
      val service = new FakeHabitService(deleteResult = IO.pure(Left(NotFound("Habit not found"))))
      val req     = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/users/1/habits/${UUID.randomUUID()}"))
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.NotFound
      }
    }

    "return 400 when id is not a valid UUID" in {
      val service = new FakeHabitService()
      val req     = Request[IO](Method.DELETE, uri"/users/1/habits/not-a-uuid")
      appWith(service).run(req).asserting { resp =>
        resp.status shouldBe Status.BadRequest
      }
    }
  }
}
