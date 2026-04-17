package com.habittracker.http

import com.habittracker.http.CommonCodecs._
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.{CreateHabitCompletionRequest, HabitCompletionResponse}
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

object CompletionCodecs {

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
