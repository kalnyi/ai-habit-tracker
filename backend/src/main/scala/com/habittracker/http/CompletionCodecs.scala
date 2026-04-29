package com.habittracker.http

import com.habittracker.http.CommonCodecs._
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.{BatchCompletionItem, BatchCompletionResponse, CreateHabitCompletionRequest, HabitCompletionResponse, SkippedCompletion}
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

  // ---------------------------------------------------------------------------
  // Phase 3 batch DTOs
  // ---------------------------------------------------------------------------

  implicit val batchCompletionItemDecoder: Decoder[BatchCompletionItem] =
    deriveDecoder[BatchCompletionItem]

  implicit val batchCompletionItemEncoder: Encoder[BatchCompletionItem] =
    deriveEncoder[BatchCompletionItem]

  implicit val skippedCompletionEncoder: Encoder[SkippedCompletion] =
    deriveEncoder[SkippedCompletion]

  implicit val skippedCompletionDecoder: Decoder[SkippedCompletion] =
    deriveDecoder[SkippedCompletion]

  implicit val batchCompletionResponseEncoder: Encoder[BatchCompletionResponse] =
    deriveEncoder[BatchCompletionResponse]

  implicit val batchCompletionResponseDecoder: Decoder[BatchCompletionResponse] =
    deriveDecoder[BatchCompletionResponse]
}
