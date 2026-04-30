package com.habittracker.http

import cats.effect.IO
import com.habittracker.client.EmbeddingClient
import com.habittracker.http.AnalyticsCodecs._
import com.habittracker.http.HabitCodecs._
import com.habittracker.model.NoteRequest
import com.habittracker.repository.NoteRepository
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._

/** Single route: POST /users/{userId}/habits/notes.
  *
  * Embeds the request body content via OpenAI Embeddings API, inserts
  * the (userId, content, embedding) row into user_notes, and returns
  * HTTP 201 with the persisted UserNote. The OpenAI HTTP call is made
  * directly at the call site — same no-abstraction pattern as
  * AnthropicClient and EmbeddingClient (ADR-008 §2, ADR-010 §2). See
  * ADR-011 §9 for the route-class separation rationale. */
final class NoteRoutes(noteRepo: NoteRepository) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "users" / LongVar(userId) / "habits" / "notes" =>
      req.as[NoteRequest].flatMap { body =>
        for {
          embedding <- EmbeddingClient.embed[IO](body.content)
          note      <- noteRepo.insert(userId, body.content, embedding)
          result    <- Created(note)
        } yield result
      }.handleErrorWith { case _: DecodeFailure =>
        BadRequest(ErrorResponse("Malformed request body"))
      }
  }
}
