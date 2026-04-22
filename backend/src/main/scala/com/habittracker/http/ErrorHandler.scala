package com.habittracker.http

import cats.effect.IO
import com.habittracker.domain.AppError
import com.habittracker.domain.AppError.{ConflictError, InvalidUuid, ValidationError}
import com.habittracker.domain.AppError.{NotFound => DomainNotFound}
import com.habittracker.http.HabitCodecs._
import org.http4s.Response
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._

/** Translates domain AppError values into http4s Response[IO] values. */
object ErrorHandler {

  def toResponse(error: AppError): IO[Response[IO]] = error match {
    case ValidationError(msg) => BadRequest(ErrorResponse(msg))
    case DomainNotFound(msg)  => NotFound(ErrorResponse(msg))
    case InvalidUuid(msg)     => BadRequest(ErrorResponse(msg))
    case ConflictError(msg)   => Conflict(ErrorResponse(msg))
  }
}
