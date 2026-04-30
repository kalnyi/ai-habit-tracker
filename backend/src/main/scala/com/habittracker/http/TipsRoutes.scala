package com.habittracker.http

import cats.effect.IO
import cats.syntax.parallel._
import com.habittracker.client.{AnthropicClient, EmbeddingClient}
import com.habittracker.http.AnalyticsCodecs._
import com.habittracker.model.{RetrievedTip, TipsResponse}
import com.habittracker.observability.RagLogger
import com.habittracker.prompt.PromptBuilder
import com.habittracker.repository.{NoteRepository, TipRepository}
import com.habittracker.service.{AnalyticsService, Deduplication}
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._

/** Single route: GET /users/{userId}/habits/tips.
  *
  * Phase 4 multi-source RAG pipeline: embed a query derived from the
  * user's habit context, retrieve top-K from BOTH the curated corpus
  * AND the user's own notes IN PARALLEL, dedupe across sources, log
  * retrieval metadata, build a two-section prompt, and call Anthropic
  * for the grounded narrative. See ADR-011 §3, §4, §5, §6. */
final class TipsRoutes(
    service:  AnalyticsService,
    tipRepo:  TipRepository,
    noteRepo: NoteRepository
) {

  private val TIPS_TOP_K:  Int = 2
  private val NOTES_TOP_K: Int = 2

  // Both retrievals are independent IO operations with no shared state.
  // Running them in parallel with `parTupled` from cats.syntax.parallel
  // halves the worst-case retrieval latency compared to a sequential
  // `.flatMap` chain. Cats IO `parTupled` runs both IOs concurrently
  // on the same compute pool the rest of the request already uses, so
  // no extra thread is allocated. HikariCP's connection pool absorbs
  // the two concurrent queries without contention, the same way the
  // analysis endpoint absorbs eight concurrent context queries
  // (ADR-009 §5).
  private def retrieveBoth(
      queryEmbedding: Vector[Float],
      userId:         Long
  ): IO[(List[RetrievedTip], List[RetrievedTip])] =
    (
      tipRepo.findSimilar(queryEmbedding, TIPS_TOP_K),
      noteRepo.findSimilar(userId, queryEmbedding, NOTES_TOP_K)
    ).parTupled

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "users" / LongVar(userId) / "habits" / "tips" =>
      for {
        ctx                <- service.buildHabitContext(userId)
        query              =  service.buildTipsQuery(ctx)
        queryEmbedding     <- EmbeddingClient.embed[IO](query)
        rawPair            <- retrieveBoth(queryEmbedding, userId)
        (rawTips, rawNotes) =  rawPair
        deduped            =  Deduplication.deduplicate(rawTips, rawNotes)
        (tips, notes)      =  deduped
        _                  <- RagLogger.logRetrieval[IO](userId, tips, notes)
        prompt             =  PromptBuilder.build(ctx, tips = tips, notes = notes)
        narrative          <- AnthropicClient.complete[IO](
                                PromptBuilder.HABIT_COACH_SYSTEM_PROMPT,
                                prompt
                              )
        response           =  TipsResponse(
                                externalTips  = tips,
                                personalNotes = notes,
                                narrative     = narrative
                              )
        result             <- Ok(response)
      } yield result
  }
}
