package com.habittracker.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.unsafe.IORuntime
import com.habittracker.http.CompletionCodecs._
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.CreateHabitCompletionRequest
import com.habittracker.service.HabitCompletionService

import java.time.LocalDate
import java.util.UUID

/** Akka HTTP routes for all HabitCompletion endpoints.
  *
  * Owns the subtree: /habits/{habitId}/completions[/{completionId}]
  *
  * Concatenated with HabitRoutes in Main. Akka HTTP tries each route in order;
  * a non-matching branch falls through. The existing HabitRoutes.path(Segment)
  * fallback only matches single-segment paths (/habits/not-a-uuid), not multi-
  * segment ones, so there is no conflict.
  *
  * This class includes its own non-UUID-habitId fallback so that requests like
  * /habits/not-a-uuid/completions return 400 regardless of route ordering.
  */
final class HabitCompletionRoutes(service: HabitCompletionService)(implicit runtime: IORuntime)
    extends JsonSupport {

  private def parseDate(
      paramName: String,
      value: Option[String]
  ): Either[String, Option[LocalDate]] =
    value match {
      case None    => Right(None)
      case Some(s) =>
        scala.util.Try(LocalDate.parse(s)).toEither.left
          .map(e => s"Invalid '$paramName' date: ${e.getMessage}")
          .map(Some(_))
    }

  val route: Route =
    handleExceptions(ErrorHandler.exceptionHandler) {
      handleRejections(ErrorHandler.rejectionHandler) {
        pathPrefix("habits") {
          concat(
            // Valid UUID habitId
            pathPrefix(JavaUUID) { habitId: UUID =>
              pathPrefix("completions") {
                concat(
                  // POST /habits/{habitId}/completions
                  // GET  /habits/{habitId}/completions[?from=...&to=...]
                  pathEndOrSingleSlash {
                    concat(
                      (post & entity(as[CreateHabitCompletionRequest])) { req =>
                        onSuccess(service.recordCompletion(habitId, req).unsafeToFuture()) {
                          case Right(resp) => complete(StatusCodes.Created, resp)
                          case Left(err)   => ErrorHandler.toRoute(err)
                        }
                      },
                      (get & parameters(
                        "from".as[String].optional,
                        "to".as[String].optional
                      )) { (fromStr, toStr) =>
                        val parsedFrom = parseDate("from", fromStr)
                        val parsedTo   = parseDate("to", toStr)
                        (parsedFrom, parsedTo) match {
                          case (Left(msg), _) =>
                            complete(StatusCodes.BadRequest, ErrorResponse(msg))
                          case (_, Left(msg)) =>
                            complete(StatusCodes.BadRequest, ErrorResponse(msg))
                          case (Right(from), Right(to)) =>
                            onSuccess(service.listCompletions(habitId, from, to).unsafeToFuture()) {
                              case Right(list) => complete(StatusCodes.OK, list)
                              case Left(err)   => ErrorHandler.toRoute(err)
                            }
                        }
                      }
                    )
                  },
                  // DELETE /habits/{habitId}/completions/{completionId}
                  path(JavaUUID) { completionId: UUID =>
                    delete {
                      onSuccess(service.deleteCompletion(habitId, completionId).unsafeToFuture()) {
                        case Right(_)  => complete(StatusCodes.NoContent)
                        case Left(err) => ErrorHandler.toRoute(err)
                      }
                    }
                  },
                  // Non-UUID completionId
                  path(Segment) { _ =>
                    complete(
                      StatusCodes.BadRequest,
                      ErrorResponse("Invalid completion id: must be a valid UUID")
                    )
                  }
                )
              }
            },
            // Non-UUID habitId on /habits/<not-uuid>/completions paths
            pathPrefix(Segment) { _ =>
              pathPrefix("completions") {
                complete(
                  StatusCodes.BadRequest,
                  ErrorResponse("Invalid habit id: must be a valid UUID")
                )
              }
            }
          )
        }
      }
    }
}
