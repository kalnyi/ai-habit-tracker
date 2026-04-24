package com.habittracker.http

import com.habittracker.model.{HabitContext, InsightResponse}
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

import java.util.UUID

object AnalyticsCodecs {

  // UUID codecs reuse the same approach as HabitCodecs.
  implicit val uuidEncoder: Encoder[UUID] =
    Encoder.encodeString.contramap(_.toString)
  implicit val uuidDecoder: Decoder[UUID] =
    Decoder.decodeString.emap { s =>
      scala.util.Try(UUID.fromString(s)).toEither.left.map(_.getMessage)
    }

  // Map[UUID, Int] — circe encodes maps whose keys have a KeyEncoder; we
  // provide one that stringifies the UUID.
  implicit val uuidKeyEncoder: io.circe.KeyEncoder[UUID] =
    io.circe.KeyEncoder.instance(_.toString)
  implicit val uuidKeyDecoder: io.circe.KeyDecoder[UUID] =
    io.circe.KeyDecoder.instance(s => scala.util.Try(UUID.fromString(s)).toOption)

  implicit val habitContextEncoder:    Encoder[HabitContext]    = deriveEncoder[HabitContext]
  implicit val habitContextDecoder:    Decoder[HabitContext]    = deriveDecoder[HabitContext]
  implicit val insightResponseEncoder: Encoder[InsightResponse] = deriveEncoder[InsightResponse]
  implicit val insightResponseDecoder: Decoder[InsightResponse] = deriveDecoder[InsightResponse]
}
