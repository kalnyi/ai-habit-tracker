package com.habittracker.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.unsafe.IORuntime
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.{CreateHabitRequest, UpdateHabitRequest}
import com.habittracker.service.HabitService

import java.util.UUID

/** Akka HTTP routes for all Habit CRUD endpoints.
  *
  * Effect-type boundary: this class uses Future at the Akka HTTP layer.
  * All IO values are bridged via `.unsafeToFuture()` using the implicit
  * IORuntime wired in Main. No Await.result is used anywhere.
  *
  * UUID validation: Akka HTTP's `JavaUUID` path matcher requires a valid UUID.
  * When a path segment does not parse as a UUID, the inner `path(JavaUUID)`
  * route does not match. We add an explicit fallback `path(Segment)` that
  * completes with 400, so that `/habits/not-a-uuid` returns 400 rather than
  * 404 (the plan mandates 400 for an invalid UUID format).
  */
final class HabitRoutes(service: HabitService)(implicit runtime: IORuntime)
    extends JsonSupport {

  val route: Route =
    handleExceptions(ErrorHandler.exceptionHandler) {
      handleRejections(ErrorHandler.rejectionHandler) {
        pathPrefix("habits") {
          concat(
            // POST /habits and GET /habits
            pathEndOrSingleSlash {
              concat(
                (post & entity(as[CreateHabitRequest])) { req =>
                  onSuccess(service.createHabit(req).unsafeToFuture()) {
                    case Right(habit) => complete(StatusCodes.Created, habit)
                    case Left(err)    => ErrorHandler.toRoute(err)
                  }
                },
                get {
                  onSuccess(service.listHabits().unsafeToFuture()) {
                    case Right(habits) => complete(StatusCodes.OK, habits)
                    case Left(err)     => ErrorHandler.toRoute(err)
                  }
                }
              )
            },
            // GET /habits/{id}, PUT /habits/{id}, DELETE /habits/{id}
            path(JavaUUID) { id: UUID =>
              concat(
                get {
                  onSuccess(service.getHabit(id).unsafeToFuture()) {
                    case Right(habit) => complete(StatusCodes.OK, habit)
                    case Left(err)    => ErrorHandler.toRoute(err)
                  }
                },
                (put & entity(as[UpdateHabitRequest])) { req =>
                  onSuccess(service.updateHabit(id, req).unsafeToFuture()) {
                    case Right(habit) => complete(StatusCodes.OK, habit)
                    case Left(err)    => ErrorHandler.toRoute(err)
                  }
                },
                delete {
                  onSuccess(service.deleteHabit(id).unsafeToFuture()) {
                    case Right(_)  => complete(StatusCodes.NoContent)
                    case Left(err) => ErrorHandler.toRoute(err)
                  }
                }
              )
            },
            // Catch non-UUID path segments and return 400 (per API contract)
            path(Segment) { _ =>
              complete(
                StatusCodes.BadRequest,
                ErrorResponse("Invalid habit id: must be a valid UUID")
              )
            }
          )
        }
      }
    }
}
