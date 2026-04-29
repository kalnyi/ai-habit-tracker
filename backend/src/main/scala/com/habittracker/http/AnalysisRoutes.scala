package com.habittracker.http

import cats.effect.IO
import com.habittracker.client.AnthropicClient
import com.habittracker.http.AnalyticsCodecs._
import com.habittracker.model.AnalysisResponse
import com.habittracker.prompt.PromptBuilder
import com.habittracker.service.AnalyticsService
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._

/** Single route: GET /users/{userId}/habits/analysis.
  *
  * Anthropic HTTP call is made directly in this handler — no wrapping trait,
  * no abstract class. See ADR-008 (Phase 1) for the no-abstraction rationale
  * and ADR-009 (Phase 2) for the route-placement decision (sibling class to
  * InsightsRoutes, not an addition to it). */
final class AnalysisRoutes(service: AnalyticsService) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "users" / LongVar(userId) / "habits" / "analysis" =>
      for {
        ctx       <- service.buildHabitContext(userId)
        prompt    =  PromptBuilder.build(ctx)
        narrative <- AnthropicClient.complete[IO](
                       PromptBuilder.HABIT_COACH_SYSTEM_PROMPT,
                       prompt
                     )
        response  =  AnalysisResponse(analytics = ctx, narrative = narrative)
        result    <- Ok(response)
      } yield result
  }
}
