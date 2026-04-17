package com.habittracker.http

import io.circe.{Decoder, Encoder}

import java.time.LocalDate

/** Shared circe codecs for primitive types used by multiple endpoints. */
object CommonCodecs {

  // ---------------------------------------------------------------------------
  // LocalDate codec — ISO 8601 YYYY-MM-DD
  // ---------------------------------------------------------------------------

  implicit val localDateEncoder: Encoder[LocalDate] =
    Encoder.encodeString.contramap(_.toString)

  implicit val localDateDecoder: Decoder[LocalDate] =
    Decoder.decodeString.emap { s =>
      scala.util.Try(LocalDate.parse(s)).toEither.left.map(_.getMessage)
    }
}
