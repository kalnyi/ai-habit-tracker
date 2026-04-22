package com.habittracker.http

import cats.effect.IO
import com.habittracker.client.AnthropicClient
import com.habittracker.http.AnalyticsCodecs._
import com.habittracker.model.InsightResponse
import com.habittracker.prompt.InsightPrompt
import com.habittracker.service.AnalyticsService
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._

/** Single route: GET /users/{userId}/habits/insights.
  *
  * The Anthropic HTTP call is made directly in this handler — no wrapping
  * trait, no abstract class. See ADR-008. */
final class InsightsRoutes(service: AnalyticsService) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "users" / LongVar(userId) / "habits" / "insights" =>
      for {
        ctx       <- service.buildHabitContext(userId)
        prompt    =  InsightPrompt.build(ctx)
        narrative <- AnthropicClient.complete[IO](InsightPrompt.SYSTEM_PROMPT, prompt)
        response  =  InsightResponse(analytics = ctx, narrative = narrative)
        result    <- Ok(response)
      } yield result
  }
}
