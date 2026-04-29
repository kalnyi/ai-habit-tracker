package com.habittracker.http

import cats.effect.IO
import com.habittracker.client.{AnthropicClient, EmbeddingClient}
import com.habittracker.http.AnalyticsCodecs._
import com.habittracker.model.TipsResponse
import com.habittracker.prompt.PromptBuilder
import com.habittracker.repository.TipRepository
import com.habittracker.service.AnalyticsService
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._

/** Single route: GET /users/{userId}/habits/tips.
  *
  * Implements the RAG pipeline: embed a query derived from the user's habit
  * context, retrieve the top-K semantically similar tips from pgvector, inject
  * them into PromptBuilder, and call the Anthropic API for a grounded narrative.
  * See ADR-010 §8 for the composition rationale. */
final class TipsRoutes(service: AnalyticsService, tipRepo: TipRepository) {

  private val TOP_K: Int = 3

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "users" / LongVar(userId) / "habits" / "tips" =>
      for {
        ctx            <- service.buildHabitContext(userId)
        query          =  service.buildTipsQuery(ctx)
        queryEmbedding <- EmbeddingClient.embed[IO](query)
        retrieved      <- tipRepo.findSimilar(queryEmbedding, TOP_K)
        prompt         =  PromptBuilder.build(ctx, retrieved)
        narrative      <- AnthropicClient.complete[IO](
                            PromptBuilder.HABIT_COACH_SYSTEM_PROMPT,
                            prompt
                          )
        response       =  TipsResponse(tips = retrieved, narrative = narrative)
        result         <- Ok(response)
      } yield result
  }
}
