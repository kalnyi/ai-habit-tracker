package com.habittracker.http

import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.{CreateHabitCompletionRequest, HabitCompletionResponse}
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

import java.time.LocalDate

object CompletionCodecs {

  // ---------------------------------------------------------------------------
  // LocalDate codec — ISO 8601 YYYY-MM-DD
  // ---------------------------------------------------------------------------

  implicit val localDateEncoder: Encoder[LocalDate] =
    Encoder.encodeString.contramap(_.toString)

  implicit val localDateDecoder: Decoder[LocalDate] =
    Decoder.decodeString.emap { s =>
      scala.util.Try(LocalDate.parse(s)).toEither.left.map(_.getMessage)
    }

  // ---------------------------------------------------------------------------
  // HabitCompletionResponse
  // ---------------------------------------------------------------------------

  implicit val habitCompletionResponseEncoder: Encoder[HabitCompletionResponse] =
    deriveEncoder[HabitCompletionResponse]

  implicit val habitCompletionResponseDecoder: Decoder[HabitCompletionResponse] =
    deriveDecoder[HabitCompletionResponse]

  // ---------------------------------------------------------------------------
  // CreateHabitCompletionRequest
  // ---------------------------------------------------------------------------

  implicit val createHabitCompletionRequestDecoder: Decoder[CreateHabitCompletionRequest] =
    deriveDecoder[CreateHabitCompletionRequest]

  implicit val createHabitCompletionRequestEncoder: Encoder[CreateHabitCompletionRequest] =
    deriveEncoder[CreateHabitCompletionRequest]
}
