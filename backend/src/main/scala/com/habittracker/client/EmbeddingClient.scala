package com.habittracker.client

import cats.effect.Async
import cats.syntax.all._
import io.circe.Json
import io.circe.parser.parse
import sttp.client3._
import sttp.client3.httpclient.cats.HttpClientCatsBackend

object EmbeddingClient {

  // An embedding is a list of 1536 floating-point numbers that represent
  // the semantic meaning of a text. Two texts whose meanings are similar
  // produce embedding vectors that are close together in 1536-dimensional
  // space; texts with unrelated meaning produce vectors that point in
  // very different directions. Cosine similarity (and pgvector's `<=>`
  // operator) measure that closeness numerically — see TipRepository for
  // how the score is computed.
  val MODEL:     String = "text-embedding-3-small"
  val API_URL:   String = "https://api.openai.com/v1/embeddings"
  val DIMENSION: Int    = 1536

  // --- key read at object-init; startup fails here if the env var is missing ---
  private val API_KEY: String =
    sys.env.get("OPENAI_API_KEY").filter(_.trim.nonEmpty).getOrElse {
      sys.error(
        "OPENAI_API_KEY environment variable is not set. " +
        "The habit tracker app cannot start without it."
      )
    }

  /** Named forcing-handle so AppResources can force object init and trigger
    * the startup failure if the key is missing. Same pattern as
    * `AnthropicClient.API_KEY_CHECK`. */
  val API_KEY_CHECK: Unit = {
    val _ = API_KEY  // touch the val so init happens now
    ()
  }

  /** Direct sttp call to OpenAI Embeddings API. The HTTP request is visible
    * at the call site — no trait, no abstract class, no DI. */
  def embed[F[_]: Async](text: String): F[Vector[Float]] = {
    val bodyJson: String =
      Json.obj(
        "model" -> Json.fromString(MODEL),
        "input" -> Json.fromString(text)
      ).noSpaces

    val request: Request[Either[String, String], Any] =
      basicRequest
        .post(uri"$API_URL")
        .header("Authorization", s"Bearer $API_KEY")
        .body(bodyJson)
        .header("Content-Type", "application/json", replaceExisting = true)
        .response(asString)

    HttpClientCatsBackend.resource[F]().use { backend =>
      request.send(backend).flatMap { resp =>
        resp.body match {
          case Right(raw) =>
            parse(raw).flatMap { json =>
              json.hcursor
                .downField("data")
                .downArray
                .downField("embedding")
                .as[Vector[Float]]
            } match {
              case Right(vec) if vec.size == DIMENSION => Async[F].pure(vec)
              case Right(vec) =>
                Async[F].raiseError(new RuntimeException(
                  s"Unexpected embedding dimension: got ${vec.size}, want $DIMENSION"
                ))
              case Left(err) =>
                Async[F].raiseError(new RuntimeException(
                  s"Failed to parse OpenAI embeddings response: ${err.getMessage}; body=$raw"
                ))
            }
          case Left(err) =>
            Async[F].raiseError(new RuntimeException(
              s"OpenAI embeddings call failed (status=${resp.code.code}): $err"
            ))
        }
      }
    }
  }
}
