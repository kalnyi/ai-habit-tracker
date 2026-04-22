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
  * UUID validation: the private UUIDVar extractor tries to parse the path segment
  * as a UUID. When it fails, the catch-all `_` patterns return 400 so that
  * /habits/not-a-uuid returns 400 instead of 404 (per API contract).
  */
final class HabitRoutes(service: HabitService) {

  private object UUIDVar {
    def unapply(str: String): Option[UUID] =
      scala.util.Try(UUID.fromString(str)).toOption
  }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "habits" =>
      service.listHabits().flatMap {
        case Right(list) => Ok(list)
        case Left(err)   => ErrorHandler.toResponse(err)
      }

    case GET -> Root / "habits" / UUIDVar(id) =>
      service.getHabit(id).flatMap {
        case Right(habit) => Ok(habit)
        case Left(err)    => ErrorHandler.toResponse(err)
      }

    case GET -> Root / "habits" / _ =>
      BadRequest(ErrorResponse("Invalid habit id: must be a valid UUID"))

    case req @ POST -> Root / "habits" =>
      req.as[CreateHabitRequest].flatMap { body =>
        service.createHabit(body).flatMap {
          case Right(habit) => Created(habit)
          case Left(err)    => ErrorHandler.toResponse(err)
        }
      }.handleErrorWith { case _: DecodeFailure =>
        BadRequest(ErrorResponse("Malformed request body"))
      }

    case req @ PUT -> Root / "habits" / UUIDVar(id) =>
      req.as[UpdateHabitRequest].flatMap { body =>
        service.updateHabit(id, body).flatMap {
          case Right(habit) => Ok(habit)
          case Left(err)    => ErrorHandler.toResponse(err)
        }
      }.handleErrorWith { case _: DecodeFailure =>
        BadRequest(ErrorResponse("Malformed request body"))
      }

    case PUT -> Root / "habits" / _ =>
      BadRequest(ErrorResponse("Invalid habit id: must be a valid UUID"))

    case DELETE -> Root / "habits" / UUIDVar(id) =>
      service.deleteHabit(id).flatMap {
        case Right(_)  => NoContent()
        case Left(err) => ErrorHandler.toResponse(err)
      }

    case DELETE -> Root / "habits" / _ =>
      BadRequest(ErrorResponse("Invalid habit id: must be a valid UUID"))
  }
}
