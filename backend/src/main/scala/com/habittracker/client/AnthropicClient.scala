package com.habittracker.client

import cats.effect.Async
import cats.syntax.all._
import sttp.client3._
import sttp.client3.httpclient.cats.HttpClientCatsBackend
import io.circe.Json
import io.circe.parser.parse

object AnthropicClient {

  val MODEL:       String = "claude-sonnet-4-20250514"
  val API_URL:     String = "https://api.anthropic.com/v1/messages"
  val API_VERSION: String = "2023-06-01"
  val MAX_TOKENS:  Int    = 1024

  // --- key read at object-init; startup fails here if the env var is missing ---
  private val API_KEY: String =
    sys.env.get("ANTHROPIC_API_KEY").filter(_.trim.nonEmpty).getOrElse {
      sys.error(
        "ANTHROPIC_API_KEY environment variable is not set. " +
        "The habit tracker app cannot start without it."
      )
    }

  /** Named forcing-handle so AppResources can force object init and trigger
    * the startup failure if the key is missing. Its value is intentionally
    * Unit — the side-effect of resolving `API_KEY` is the whole point. */
  val API_KEY_CHECK: Unit = {
    val _ = API_KEY  // touch the val so init happens now
    ()
  }

  /** Direct sttp call to Anthropic Messages API. The HTTP request is visible
    * at the call site — no trait, no abstract class, no DI. */
  def complete[F[_]: Async](
      systemPrompt: String,
      userMessage:  String
  ): F[String] = {
    val bodyJson: String =
      Json.obj(
        "model"      -> Json.fromString(MODEL),
        "max_tokens" -> Json.fromInt(MAX_TOKENS),
        "system"     -> Json.fromString(systemPrompt),
        "messages"   -> Json.arr(
          Json.obj(
            "role"    -> Json.fromString("user"),
            "content" -> Json.fromString(userMessage)
          )
        )
      ).noSpaces

    val request: Request[Either[String, String], Any] =
      basicRequest
        .post(uri"$API_URL")
        .header("x-api-key", API_KEY)
        .header("anthropic-version", API_VERSION)
        .header("content-type", "application/json")
        .body(bodyJson)
        .response(asString)

    HttpClientCatsBackend.resource[F]().use { backend =>
      request.send(backend).flatMap { resp =>
        resp.body match {
          case Right(raw) =>
            parse(raw).flatMap { json =>
              json.hcursor
                .downField("content")
                .downArray
                .downField("text")
                .as[String]
            } match {
              case Right(text) => Async[F].pure(text)
              case Left(err)   =>
                Async[F].raiseError(new RuntimeException(
                  s"Failed to parse Anthropic response: ${err.getMessage}; body=$raw"
                ))
            }
          case Left(err) =>
            Async[F].raiseError(new RuntimeException(
              s"Anthropic API call failed (status=${resp.code.code}): $err"
            ))
        }
      }
    }
  }
}
