package com.habittracker.http

/** Shared error envelope for every 4xx and 5xx response in this API. */
final case class ErrorResponse(message: String)
