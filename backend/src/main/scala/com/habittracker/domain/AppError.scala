package com.habittracker.domain

/** Sealed hierarchy of application-level errors.
  *
  * Database failures are not modelled here — they propagate as IO failures and
  * are caught by the Akka HTTP ExceptionHandler in ErrorHandler, which maps
  * them to 500 responses.
  */
sealed trait AppError

object AppError {
  final case class ValidationError(message: String) extends AppError
  final case class NotFound(message: String) extends AppError
  final case class InvalidUuid(message: String) extends AppError
}
