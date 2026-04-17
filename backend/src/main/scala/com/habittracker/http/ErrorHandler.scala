package com.habittracker.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, MalformedRequestContentRejection, RejectionHandler, Route}
import com.habittracker.domain.AppError
import com.habittracker.domain.AppError.{InvalidUuid, NotFound, ValidationError}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import org.slf4j.LoggerFactory

object ErrorHandler extends FailFastCirceSupport {

  import HabitCodecs._

  private val log = LoggerFactory.getLogger(getClass)

  /** Translates an AppError into the appropriate HTTP response. */
  def toRoute(error: AppError): Route = error match {
    case ValidationError(msg) =>
      complete(StatusCodes.BadRequest, ErrorResponse(msg))
    case NotFound(msg) =>
      complete(StatusCodes.NotFound, ErrorResponse(msg))
    case InvalidUuid(msg) =>
      complete(StatusCodes.BadRequest, ErrorResponse(msg))
  }

  /** Maps any unhandled Throwable to 500 + a generic ErrorResponse. */
  val exceptionHandler: ExceptionHandler =
    ExceptionHandler { case ex: Throwable =>
      log.error("Unhandled exception", ex)
      complete(
        StatusCodes.InternalServerError,
        ErrorResponse("An internal server error occurred")
      )
    }

  /** Maps well-known Akka HTTP rejections (malformed JSON, missing fields,
    * wrong content-type) to 400 + ErrorResponse.
    */
  val rejectionHandler: RejectionHandler =
    RejectionHandler
      .newBuilder()
      .handle { case MalformedRequestContentRejection(msg, _) =>
        complete(
          StatusCodes.BadRequest,
          ErrorResponse(s"Malformed request body: $msg")
        )
      }
      .handleNotFound {
        complete(
          StatusCodes.NotFound,
          ErrorResponse("The requested resource was not found")
        )
      }
      .result()
      .withFallback(RejectionHandler.default)
}
