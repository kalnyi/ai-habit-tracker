package com.habittracker.http

import cats.effect.IO
import cats.syntax.all._
import com.habittracker.http.CompletionCodecs._
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.CreateHabitCompletionRequest
import com.habittracker.service.HabitCompletionService
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._

import java.time.LocalDate
import java.util.UUID

/** http4s routes for all HabitCompletion endpoints.
  *
  * Owns the subtree: /users/{userId}/habits/{habitId}/completions[/{completionId}]
  *
  * UUID patterns come before wildcard `_` patterns so that valid UUIDs are
  * handled correctly and non-UUIDs return 400 (per API contract).
  */
final class HabitCompletionRoutes(service: HabitCompletionService) {

  private object UUIDVar {
    def unapply(str: String): Option[UUID] =
      scala.util.Try(UUID.fromString(str)).toOption
  }

  private object FromParam extends OptionalQueryParamDecoderMatcher[String]("from")
  private object ToParam   extends OptionalQueryParamDecoderMatcher[String]("to")

  private def parseDate(name: String, s: String): Either[String, LocalDate] =
    scala.util.Try(LocalDate.parse(s)).toEither.left.map(e => s"Invalid '$name' date: ${e.getMessage}")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "users" / LongVar(userId) / "habits" / UUIDVar(habitId) / "completions" =>
      req.as[CreateHabitCompletionRequest].flatMap { body =>
        service.recordCompletion(userId, habitId, body).flatMap {
          case Right(resp) => Created(resp)
          case Left(err)   => ErrorHandler.toResponse(err)
        }
      }.handleErrorWith { case _: DecodeFailure =>
        BadRequest(ErrorResponse("Malformed request body"))
      }

    case POST -> Root / "users" / LongVar(_) / "habits" / _ / "completions" =>
      BadRequest(ErrorResponse("Invalid habit id: must be a valid UUID"))

    case GET -> Root / "users" / LongVar(userId) / "habits" / UUIDVar(habitId) / "completions" :? FromParam(fromOpt) +& ToParam(toOpt) =>
      val from = fromOpt.traverse(parseDate("from", _))
      val to   = toOpt.traverse(parseDate("to", _))
      (from, to) match {
        case (Left(msg), _) => BadRequest(ErrorResponse(msg))
        case (_, Left(msg)) => BadRequest(ErrorResponse(msg))
        case (Right(f), Right(t)) =>
          service.listCompletions(userId, habitId, f, t).flatMap {
            case Right(list) => Ok(list)
            case Left(err)   => ErrorHandler.toResponse(err)
          }
      }

    case GET -> Root / "users" / LongVar(_) / "habits" / _ / "completions" =>
      BadRequest(ErrorResponse("Invalid habit id: must be a valid UUID"))

    case DELETE -> Root / "users" / LongVar(userId) / "habits" / UUIDVar(habitId) / "completions" / UUIDVar(completionId) =>
      service.deleteCompletion(userId, habitId, completionId).flatMap {
        case Right(_)  => NoContent()
        case Left(err) => ErrorHandler.toResponse(err)
      }

    case DELETE -> Root / "users" / LongVar(_) / "habits" / UUIDVar(_) / "completions" / _ =>
      BadRequest(ErrorResponse("Invalid completion id: must be a valid UUID"))

    case DELETE -> Root / "users" / LongVar(_) / "habits" / _ / "completions" / _ =>
      BadRequest(ErrorResponse("Invalid habit id: must be a valid UUID"))
  }
}
