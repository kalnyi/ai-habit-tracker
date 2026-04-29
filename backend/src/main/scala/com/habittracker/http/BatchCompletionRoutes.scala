package com.habittracker.http

import cats.effect.IO
import com.habittracker.http.CompletionCodecs._
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.BatchCompletionItem
import com.habittracker.service.HabitCompletionService
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._

/** Single route: POST /users/{userId}/habits/completions/batch.
  *
  * Partial-success contract: each item is processed independently. Items
  * that succeed appear in `inserted`; items rejected by the unique
  * constraint or a missing/soft-deleted habit appear in `skipped`. HTTP
  * 200 is returned even when `inserted` is empty. See ADR-010 §9. */
final class BatchCompletionRoutes(service: HabitCompletionService) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "users" / LongVar(userId) / "habits" / "completions" / "batch" =>
      req.as[List[BatchCompletionItem]].flatMap { items =>
        service.recordCompletionBatch(userId, items).flatMap(Ok(_))
      }.handleErrorWith { case _: DecodeFailure =>
        BadRequest(ErrorResponse("Malformed request body"))
      }
  }
}
