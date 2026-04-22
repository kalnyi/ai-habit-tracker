package com.habittracker.http

import cats.effect.IO
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.{CreateHabitRequest, UpdateHabitRequest}
import com.habittracker.service.HabitService
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._

import java.util.UUID

/** http4s routes for all Habit CRUD endpoints.
  *
  * All routes are rooted under /users/{userId}/habits/...
  * UUID validation: the private UUIDVar extractor tries to parse the path segment
  * as a UUID. When it fails, the catch-all `_` patterns return 400 so that
  * /users/1/habits/not-a-uuid returns 400 instead of 404 (per API contract).
  * Non-numeric {userId} falls through to 404 via orNotFound (LongVar fails to match).
  */
final class HabitRoutes(service: HabitService) {

  private object UUIDVar {
    def unapply(str: String): Option[UUID] =
      scala.util.Try(UUID.fromString(str)).toOption
  }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "users" / LongVar(userId) / "habits" =>
      service.listHabits(userId).flatMap {
        case Right(list) => Ok(list)
        case Left(err)   => ErrorHandler.toResponse(err)
      }

    case GET -> Root / "users" / LongVar(userId) / "habits" / UUIDVar(id) =>
      service.getHabit(userId, id).flatMap {
        case Right(habit) => Ok(habit)
        case Left(err)    => ErrorHandler.toResponse(err)
      }

    case GET -> Root / "users" / LongVar(_) / "habits" / _ =>
      BadRequest(ErrorResponse("Invalid habit id: must be a valid UUID"))

    case req @ POST -> Root / "users" / LongVar(userId) / "habits" =>
      req.as[CreateHabitRequest].flatMap { body =>
        service.createHabit(userId, body).flatMap {
          case Right(habit) => Created(habit)
          case Left(err)    => ErrorHandler.toResponse(err)
        }
      }.handleErrorWith { case _: DecodeFailure =>
        BadRequest(ErrorResponse("Malformed request body"))
      }

    case req @ PUT -> Root / "users" / LongVar(userId) / "habits" / UUIDVar(id) =>
      req.as[UpdateHabitRequest].flatMap { body =>
        service.updateHabit(userId, id, body).flatMap {
          case Right(habit) => Ok(habit)
          case Left(err)    => ErrorHandler.toResponse(err)
        }
      }.handleErrorWith { case _: DecodeFailure =>
        BadRequest(ErrorResponse("Malformed request body"))
      }

    case PUT -> Root / "users" / LongVar(_) / "habits" / _ =>
      BadRequest(ErrorResponse("Invalid habit id: must be a valid UUID"))

    case DELETE -> Root / "users" / LongVar(userId) / "habits" / UUIDVar(id) =>
      service.deleteHabit(userId, id).flatMap {
        case Right(_)  => NoContent()
        case Left(err) => ErrorHandler.toResponse(err)
      }

    case DELETE -> Root / "users" / LongVar(_) / "habits" / _ =>
      BadRequest(ErrorResponse("Invalid habit id: must be a valid UUID"))
  }
}
