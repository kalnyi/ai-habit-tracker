package com.habittracker.http

import com.habittracker.http.dto.{CreateHabitRequest, HabitResponse, UpdateHabitRequest}
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

import java.time.Instant
import java.util.UUID

object HabitCodecs {

  // ---------------------------------------------------------------------------
  // Primitive codecs
  // ---------------------------------------------------------------------------

  implicit val instantEncoder: Encoder[Instant] =
    Encoder.encodeString.contramap(_.toString)

  implicit val instantDecoder: Decoder[Instant] =
    Decoder.decodeString.emap { s =>
      scala.util.Try(Instant.parse(s)).toEither.left.map(_.getMessage)
    }

  implicit val uuidEncoder: Encoder[UUID] =
    Encoder.encodeString.contramap(_.toString)

  implicit val uuidDecoder: Decoder[UUID] =
    Decoder.decodeString.emap { s =>
      scala.util.Try(UUID.fromString(s)).toEither.left.map(_.getMessage)
    }

  // ---------------------------------------------------------------------------
  // HabitResponse — both Encoder (for responses) and Decoder (for tests)
  // ---------------------------------------------------------------------------

  implicit val habitResponseEncoder: Encoder[HabitResponse] =
    deriveEncoder[HabitResponse]

  implicit val habitResponseDecoder: Decoder[HabitResponse] =
    deriveDecoder[HabitResponse]

  // ---------------------------------------------------------------------------
  // CreateHabitRequest — both Decoder (for incoming body) and Encoder (for tests)
  // ---------------------------------------------------------------------------

  implicit val createHabitRequestDecoder: Decoder[CreateHabitRequest] =
    deriveDecoder[CreateHabitRequest]

  implicit val createHabitRequestEncoder: Encoder[CreateHabitRequest] =
    deriveEncoder[CreateHabitRequest]

  // ---------------------------------------------------------------------------
  // UpdateHabitRequest — both Decoder (for incoming body) and Encoder (for tests)
  // ---------------------------------------------------------------------------

  implicit val updateHabitRequestDecoder: Decoder[UpdateHabitRequest] =
    deriveDecoder[UpdateHabitRequest]

  implicit val updateHabitRequestEncoder: Encoder[UpdateHabitRequest] =
    deriveEncoder[UpdateHabitRequest]

  // ---------------------------------------------------------------------------
  // ErrorResponse — both Encoder and Decoder
  // ---------------------------------------------------------------------------

  implicit val errorResponseEncoder: Encoder[ErrorResponse] =
    deriveEncoder[ErrorResponse]

  implicit val errorResponseDecoder: Decoder[ErrorResponse] =
    deriveDecoder[ErrorResponse]
}
