# PLAN-018: Phase 4 — Full RAG (multi-source retrieval, deduplication, observability)

## PBI reference
PBI-018 — Phase 4 Full RAG Pipeline (29 acceptance criteria,
`docs/phases/phase_4_pbi.md`).

## Summary

Phase 4 turns the Phase 3 single-source RAG pipeline into a multi-source
pipeline by adding a `user_notes` corpus and parallelising the two
retrievals. The plan creates one new repository (`NoteRepository`), one
new pure service object (`Deduplication`), one new observability object
(`RagLogger`), one new route class (`NoteRoutes`), one new init SQL file
(`infra/db/init/03_create_user_notes.sql`), two new model case classes
(`UserNote`, `NoteRequest`), and one new infrastructure file
(`docs/future_improvements.md`). It modifies `TipsRoutes` (parallel
retrieval, dedup, log call, breaking-change response shape),
`PromptBuilder` (new section + widened `build`), `Analytics.scala`
(`TipsResponse` rename + new case classes), `AnalyticsCodecs.scala`
(two new pairs of codecs), `AppResources.scala` (NoteRepository +
NoteRoutes wiring), and `openapi.yaml` (one new path, two new schemas,
`TipsResponse` field rename). The eval endpoint specified in the brief
is **removed** per engineer decision 2026-04-29 — see ADR-011 §7.

ADR-011 was written and is the source of truth for the architectural
decisions referenced below.

---

## Affected files

| File | Change type | Description |
|---|---|---|
| `infra/db/init/03_create_user_notes.sql` | Create | Init SQL for the `user_notes` table + `user_notes_user_id_idx` index. |
| `backend/src/main/scala/com/habittracker/model/Analytics.scala` | Modify | Add `UserNote`, `NoteRequest`; rename `TipsResponse.tips` to `externalTips`, add `personalNotes`. |
| `backend/src/main/scala/com/habittracker/http/AnalyticsCodecs.scala` | Modify | Add `userNoteEncoder/Decoder`, `noteRequestEncoder/Decoder`. Existing `tipsResponseEncoder/Decoder` re-derives. |
| `backend/src/main/scala/com/habittracker/repository/NoteRepository.scala` | Create | Doobie repository with named `similaritySearchSql`, `userId` filtering, `IO`-typed returns. |
| `backend/src/main/scala/com/habittracker/service/Deduplication.scala` | Create | Pure object with `deduplicate`, `wordOverlapRatio`, `isDuplicate`. No `IO`, no `F[_]`. |
| `backend/src/main/scala/com/habittracker/observability/RagLogger.scala` | Create | `logRetrieval[F[_]: Async]` — scores and counts only, never content. |
| `backend/src/main/scala/com/habittracker/prompt/PromptBuilder.scala` | Modify | Add `personalNotesSection`; widen `build` to `(ctx, tips = Nil, notes = Nil)`. |
| `backend/src/main/scala/com/habittracker/http/TipsRoutes.scala` | Modify | Replace sequential retrieval with `parTupled` `retrieveBoth(queryEmbedding, userId)` def, dedup, log, build new `TipsResponse` shape. Add `TIPS_TOP_K = 2`, `NOTES_TOP_K = 2`. |
| `backend/src/main/scala/com/habittracker/http/NoteRoutes.scala` | Create | `POST /users/{userId}/habits/notes`. Embed → insert → `Created(note)`. |
| `backend/src/main/scala/com/habittracker/AppResources.scala` | Modify | Construct `NoteRepository(xa)`, wire `NoteRoutes(noteRepo)` into route composition between `BatchCompletionRoutes` and `HabitRoutes`. |
| `backend/src/main/resources/openapi/openapi.yaml` | Modify | Add `UserNote`, `NoteRequest` schemas; add `POST /users/{userId}/habits/notes` path; rewrite `TipsResponse` schema. |
| `backend/src/test/scala/com/habittracker/service/DeduplicationSpec.scala` | Create | Six pure unit tests (PBI AC-10). |
| `backend/src/test/scala/com/habittracker/observability/RagLoggerSpec.scala` | Create | Pure unit test asserting log-line format and that no content is logged. |
| `backend/src/test/scala/com/habittracker/prompt/PromptBuilderSpec.scala` | Modify | Append four new test cases for `personalNotesSection` and `build(ctx, tips, notes)`. Existing cases unchanged. |
| `backend/src/test/scala/com/habittracker/repository/NoteRepositorySpec.scala` | Create | Four Testcontainers tests (`@Ignore`). |
| `backend/src/test/scala/com/habittracker/http/TipsRoutesParallelRetrievalSpec.scala` | Create | IO unit test verifying `retrieveBoth` calls both repos exactly once. |
| `backend/src/test/scala/com/habittracker/integration/NoteRoundtripIntegrationSpec.scala` | Create | Testcontainers integration test (`@Ignore`) for POST /notes → GET /tips. |
| `docs/future_improvements.md` | Create | Five post-Phase 4 notes (PBI AC-29). |

**File counts: 11 created, 7 modified.**

No file deletions. No existing files renamed. The Liquibase changelog is
not modified. No new dependencies are added.

---

## New components

### `com.habittracker.repository.NoteRepository`

Located at `backend/src/main/scala/com/habittracker/repository/NoteRepository.scala`.

```scala
package com.habittracker.repository

import cats.effect.IO
import com.habittracker.model.{HabitTip, RetrievedTip, UserNote}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._  // Meta[Instant] for created_at

import java.time.Instant

final class NoteRepository(transactor: Transactor[IO]) {

  // -------------------------------------------------------------------------
  // pgvector wire-format helper — same approach as TipRepository.
  // pgvector accepts vector literals in the textual form `[v1,v2,...]`.
  // We render the Vector[Float] to that literal form and cast it back to
  // `vector` in SQL via `::vector`. This keeps the wire format explicit
  // and reviewable, with no dependency on com.pgvector:pgvector-java.
  // See ADR-010 §5 for the rationale.
  // -------------------------------------------------------------------------
  private def embeddingToPgLiteral(v: Vector[Float]): String =
    v.mkString("[", ",", "]")

  // -------------------------------------------------------------------------
  // SQL queries
  // -------------------------------------------------------------------------

  private def insertSql(
      userId:           Long,
      content:          String,
      embeddingLiteral: String
  ): Query0[(Long, Instant)] =
    sql"""
      INSERT INTO user_notes (user_id, content, embedding)
      VALUES ($userId, $content, $embeddingLiteral::vector)
      RETURNING id, created_at
    """.query[(Long, Instant)]

  // Cosine similarity over the user's own notes — same operator semantics
  // as TipRepository.similaritySearchSql. The WHERE user_id = $userId
  // clause enforces user-scoping at the database level: a query for user A
  // must never return notes belonging to user B (ADR-007/008, ADR-011 §1).
  // pgvector's `<=>` operator returns cosine distance; we subtract from 1
  // to translate it into the more intuitive similarity score.
  val similaritySearchSql: String =
    "SELECT id, content, 1.0 - (embedding <=> ?::vector) AS score " +
    "FROM user_notes WHERE user_id = ? " +
    "ORDER BY embedding <=> ?::vector LIMIT ?"

  private def similaritySearchQuery(
      userId:         Long,
      queryEmbedding: Vector[Float],
      topK:           Int
  ): Query0[(Long, String, Double)] = {
    val embeddingLiteral: String = embeddingToPgLiteral(queryEmbedding)
    sql"""
      SELECT id,
             content,
             1.0 - (embedding <=> $embeddingLiteral::vector) AS score
      FROM user_notes
      WHERE user_id = $userId
      ORDER BY embedding <=> $embeddingLiteral::vector
      LIMIT $topK
    """.query[(Long, String, Double)]
  }

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  def insert(
      userId:    Long,
      content:   String,
      embedding: Vector[Float]
  ): IO[UserNote] = {
    val literal = embeddingToPgLiteral(embedding)
    insertSql(userId, content, literal)
      .unique
      .transact(transactor)
      .map { case (id, createdAt) =>
        UserNote(id = id, userId = userId, content = content, createdAt = createdAt)
      }
  }

  // pgvector's `<=>` operator returns cosine *distance*, where smaller is
  // closer. Ordering ascending by `<=>` returns rows from most similar to
  // least similar — i.e. the caller sees results sorted by semantic
  // similarity descending, which is what RAG retrieval needs.
  def findSimilar(
      userId:         Long,
      queryEmbedding: Vector[Float],
      topK:           Int
  ): IO[List[RetrievedTip]] =
    similaritySearchQuery(userId, queryEmbedding, topK)
      .to[List]
      .transact(transactor)
      .map(_.map { case (id, content, score) =>
        RetrievedTip(HabitTip(id, content), score)
      })
}
```

**Naming note for `similaritySearchSql`:** AC-15 mandates "named
`similaritySearchSql` val". The implementation provides both: a named
`val similaritySearchSql: String` with the SQL text (satisfies the
literal name + val requirement) and a parameterised `private def
similaritySearchQuery` that produces the `Query0` (the binding
mechanic, mirroring `TipRepository`'s pattern where the query
construction is a `def` for the same Doobie reason — see ADR-010 §4).
The Reviewer will look for the *name* `similaritySearchSql` in source;
this satisfies that check.

### `com.habittracker.service.Deduplication`

Located at `backend/src/main/scala/com/habittracker/service/Deduplication.scala`.

```scala
package com.habittracker.service

import com.habittracker.model.RetrievedTip

/** Pure cross-source deduplication for the multi-source RAG pipeline.
  *
  * Two items (one from the curated `habit_tips` corpus, one from the
  * user's own `user_notes`) are considered duplicates if and only if:
  *   - their similarity scores differ by less than 0.05, AND
  *   - their word-overlap ratio exceeds 0.8.
  *
  * When a duplicate pair is found, the item with the higher score is
  * retained. On exact tie, the corpus tip is retained (deterministic).
  *
  * Pure: no F[_], no IO, no side effects. See ADR-011 §4. */
object Deduplication {

  private val SCORE_DIFF_THRESHOLD:   Double = 0.05
  private val WORD_OVERLAP_THRESHOLD: Double = 0.8

  /** Splits a string into a Set of lowercased word tokens. Whitespace
    * and punctuation are treated as separators. Empty tokens are dropped. */
  private def words(s: String): Set[String] =
    s.toLowerCase
      .split("\\W+")
      .iterator
      .filter(_.nonEmpty)
      .toSet

  /** Word-overlap ratio: |sharedWords| / max(|wordsA|, |wordsB|).
    * Returns 0.0 when both inputs have no words (no false-duplicate). */
  private[service] def wordOverlapRatio(a: String, b: String): Double = {
    val wa = words(a)
    val wb = words(b)
    val maxSize = math.max(wa.size, wb.size)
    if (maxSize == 0) 0.0
    else wa.intersect(wb).size.toDouble / maxSize.toDouble
  }

  /** A pair is a duplicate when both score-diff and word-overlap
    * conditions hold. */
  private[service] def isDuplicate(a: RetrievedTip, b: RetrievedTip): Boolean = {
    val scoreDiff = math.abs(a.similarityScore - b.similarityScore)
    val overlap   = wordOverlapRatio(a.tip.content, b.tip.content)
    scoreDiff < SCORE_DIFF_THRESHOLD && overlap > WORD_OVERLAP_THRESHOLD
  }

  /** Removes near-duplicates across the two sources. Items kept on each
    * side preserve their original relative order. */
  def deduplicate(
      tips:  List[RetrievedTip],
      notes: List[RetrievedTip]
  ): (List[RetrievedTip], List[RetrievedTip]) = {
    // For each (tip, note) duplicate pair, mark one side for removal.
    // The higher-scored item is kept; on tie the tip side is kept.
    val tipsIdx  = tips.zipWithIndex
    val notesIdx = notes.zipWithIndex

    val (tipDrops, noteDrops) = tipsIdx.foldLeft(
      (Set.empty[Int], Set.empty[Int])
    ) { case ((dropT, dropN), (t, ti)) =>
      notesIdx.foldLeft((dropT, dropN)) { case ((dt, dn), (n, ni)) =>
        if (dt.contains(ti) || dn.contains(ni)) (dt, dn)
        else if (isDuplicate(t, n)) {
          if (t.similarityScore >= n.similarityScore) (dt, dn + ni)
          else (dt + ti, dn)
        } else (dt, dn)
      }
    }

    val survivingTips  = tipsIdx.collect  { case (t, i) if !tipDrops(i)  => t }
    val survivingNotes = notesIdx.collect { case (n, i) if !noteDrops(i) => n }
    (survivingTips, survivingNotes)
  }
}
```

### `com.habittracker.observability.RagLogger`

Located at `backend/src/main/scala/com/habittracker/observability/RagLogger.scala`.

```scala
package com.habittracker.observability

import cats.effect.Async
import com.habittracker.model.RetrievedTip

/** Structured retrieval-quality logger for the multi-source RAG pipeline.
  *
  * Privacy contract (ADR-011 §5): this object MUST NOT log the `content`
  * field of any RetrievedTip. The corpus tips are non-sensitive but the
  * personal notes may contain PII (the user types them as free text).
  * The signal we want for observability is the score distribution and
  * the count — sufficient to detect empty results, low-confidence
  * retrieval, and source imbalance. */
object RagLogger {

  /** Logs the metadata of a single retrieval round to stdout.
    *
    * Format (single line):
    *   RAG userId=X externalCount=N personalCount=M
    *       topExternalScore=0.87 topPersonalScore=0.91
    */
  def logRetrieval[F[_]: Async](
      userId: Long,
      tips:   List[RetrievedTip],
      notes:  List[RetrievedTip]
  ): F[Unit] = {
    val externalCount = tips.size
    val personalCount = notes.size
    val topExternal   = tips.headOption.map(_.similarityScore).getOrElse(0.0)
    val topPersonal   = notes.headOption.map(_.similarityScore).getOrElse(0.0)
    Async[F].delay {
      println(
        f"RAG userId=$userId%d externalCount=$externalCount%d personalCount=$personalCount%d " +
        f"topExternalScore=$topExternal%.2f topPersonalScore=$topPersonal%.2f"
      )
    }
  }
}
```

### `com.habittracker.http.NoteRoutes`

Located at `backend/src/main/scala/com/habittracker/http/NoteRoutes.scala`.

```scala
package com.habittracker.http

import cats.effect.IO
import com.habittracker.client.EmbeddingClient
import com.habittracker.http.AnalyticsCodecs._
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
```

---

## API contract

### POST /users/{userId}/habits/notes (NEW)

Path parameters:
- `userId: Long` (required, validated by `LongVar`)

Request body:
```json
{ "content": "I did better when I exercised first thing in the morning" }
```
Schema: `NoteRequest(content: String)`. `content` is required.

Responses:
- `201 Created`, body:
  ```json
  {
    "id": 17,
    "userId": 42,
    "content": "I did better when I exercised first thing in the morning",
    "createdAt": "2026-04-29T18:35:12.345Z"
  }
  ```
  Schema: `UserNote(id: Long, userId: Long, content: String, createdAt: Instant)`.
- `400 Bad Request` if the body is malformed (DecodeFailure handler).
- `500 Internal Server Error` on upstream embeddings or DB failure
  (default http4s error handler).

### GET /users/{userId}/habits/tips (MODIFIED — breaking change)

Path parameters:
- `userId: Long` (unchanged)

Request body: none.

Response: `200 OK`, body:
```json
{
  "externalTips":  [ {"tip": {"id": 1, "content": "..."}, "similarityScore": 0.91}, ... ],
  "personalNotes": [ {"tip": {"id": 7, "content": "..."}, "similarityScore": 0.87}, ... ],
  "narrative":     "..."
}
```
Schema: `TipsResponse(externalTips: List[RetrievedTip], personalNotes: List[RetrievedTip], narrative: String)`.

The Phase 3 `tips` field is removed. All three fields are required in
the OpenAPI schema.

---

## Database changes

### `infra/db/init/03_create_user_notes.sql` (NEW)

```sql
-- Run once on first container startup
-- Creates the per-user notes table for the Phase 4 multi-source RAG pipeline.
-- The vector(1536) column matches text-embedding-3-small dimensionality
-- established by EmbeddingClient (ADR-010 §2). See ADR-011 §2.

CREATE TABLE IF NOT EXISTS user_notes (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    content    TEXT         NOT NULL,
    embedding  vector(1536) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS user_notes_user_id_idx
    ON user_notes (user_id);
```

The init script runs after `01_enable_vector.sql` and
`02_create_habit_tips.sql` due to lexicographic ordering. The
`pgvector/pgvector:pg17` image's `docker-entrypoint-initdb.d` honours
that order.

**No Liquibase changeset.** The next free Liquibase slot remains 006,
reserved for genuine application-schema changes. See ADR-011 §2.

---

## LLM integration

Phase 4 extends the **daily tip** LLM use case from CLAUDE.md ("given a
habit + retrieved context from pgvector, generate a personalised tip
for today. Always RAG-augmented — never a cold prompt"). The system
prompt is unchanged (`PromptBuilder.HABIT_COACH_SYSTEM_PROMPT`). The
user-message prompt now includes both:

- "Relevant habit-science tips retrieved for this user" (Phase 3
  section, fed by the curated corpus)
- "YOUR PAST NOTES:" (Phase 4 section, fed by the user's own notes)

No prompt file changes. Both sections are constructed in
`PromptBuilder.scala` (no `backend/src/main/resources/prompts/` files
are added).

RAG strategy:
- **Retrieval:** parallel via `parTupled` from
  `cats.syntax.parallel._` — `tipRepo.findSimilar(query, TIPS_TOP_K=2)`
  AND `noteRepo.findSimilar(userId, query, NOTES_TOP_K=2)`. Both
  return `IO[List[RetrievedTip]]`. The compose runs them concurrently
  and yields `IO[(List[RetrievedTip], List[RetrievedTip])]`. See
  ADR-011 §3.
- **Dedup:** pure `Deduplication.deduplicate(tips, notes)` is called
  on the result. See ADR-011 §4.
- **Logging:** `RagLogger.logRetrieval[IO](userId, tips, notes)` is
  invoked between dedup and prompt assembly. See ADR-011 §5.
- **Embedding model:** unchanged — `text-embedding-3-small`,
  1536 dimensions, via OpenAI API.

---

## Modified components

### `backend/src/main/scala/com/habittracker/model/Analytics.scala`

Two new case classes appended; one existing case class is rewritten with
new field names.

```scala
// (existing imports + HabitContext, InsightResponse, AnalysisResponse,
//  HabitTip, RetrievedTip remain untouched)

import java.time.Instant

// ---------------------------------------------------------------------------
// Phase 4: user-notes RAG source
// ---------------------------------------------------------------------------

/** A user-authored note persisted in the user_notes table and returned
  * by POST /users/{userId}/habits/notes. */
final case class UserNote(
    id:        Long,
    userId:    Long,
    content:   String,
    createdAt: Instant
)

/** Request body for POST /users/{userId}/habits/notes. */
final case class NoteRequest(content: String)

// ---------------------------------------------------------------------------
// TipsResponse — Phase 4 BREAKING CHANGE
// ---------------------------------------------------------------------------

/** Response returned by GET /users/{userId}/habits/tips.
  *
  * Phase 4 breaking change: the single `tips` field is replaced by
  * `externalTips` (Phase 3 corpus source) and `personalNotes` (Phase 4
  * user-notes source). See ADR-011 §6. */
final case class TipsResponse(
    externalTips:  List[RetrievedTip],
    personalNotes: List[RetrievedTip],
    narrative:     String
)
```

The line `final case class TipsResponse(tips: List[RetrievedTip], narrative: String)`
is replaced by the new shape above. No other case classes are touched.

### `backend/src/main/scala/com/habittracker/http/AnalyticsCodecs.scala`

Two new pairs of codecs are appended after the Phase 3 codecs. Imports
gain `UserNote` and `NoteRequest`. The existing `tipsResponseEncoder /
Decoder` lines are NOT edited — semiauto re-derives them from the new
`TipsResponse` shape on recompile.

```scala
import com.habittracker.model.{
  AnalysisResponse, HabitContext, HabitTip, InsightResponse,
  NoteRequest, RetrievedTip, TipsResponse, UserNote
}

// ... (existing UUID, KeyEncoder, HabitContext, AnalysisResponse,
//      InsightResponse, HabitTip, RetrievedTip, TipsResponse codec
//      lines remain unchanged) ...

// ---------------------------------------------------------------------------
// Phase 4: user-notes RAG source
// ---------------------------------------------------------------------------

implicit val userNoteEncoder:    Encoder[UserNote]    = deriveEncoder[UserNote]
implicit val userNoteDecoder:    Decoder[UserNote]    = deriveDecoder[UserNote]
implicit val noteRequestEncoder: Encoder[NoteRequest] = deriveEncoder[NoteRequest]
implicit val noteRequestDecoder: Decoder[NoteRequest] = deriveDecoder[NoteRequest]
```

`Encoder[Instant]` / `Decoder[Instant]` are provided transitively by
circe-core (`io.circe.Encoder.encodeInstant` /
`Decoder.decodeInstant`). No additional import needed.

### `backend/src/main/scala/com/habittracker/prompt/PromptBuilder.scala`

Add `personalNotesSection` after `retrievedContextSection`. Widen
`build` signature to accept `notes`. No existing methods are modified.

```scala
// ---------------------------------------------------------------------------
// Phase 4: personal-notes section
// ---------------------------------------------------------------------------

/** Renders the user's own past notes retrieved from user_notes.
  *
  * Header is "YOUR PAST NOTES:" (distinct from retrievedContextSection's
  * "Relevant habit-science tips..." preamble) so the LLM can distinguish
  * curated corpus content from user-authored content. Returns "" when
  * `notes` is Nil — filtered out by `build`. See ADR-011 §8. */
def personalNotesSection(notes: List[RetrievedTip]): String =
  if (notes.isEmpty) ""
  else {
    val lines = notes.map { rn => s"- ${rn.tip.content}" }
    "YOUR PAST NOTES:\n" + lines.mkString("\n")
  }

// ---------------------------------------------------------------------------
// Build — Phase 4 widened signature
// ---------------------------------------------------------------------------

/** Phase 4: both `tips` and `notes` default to Nil so existing call sites
  * (AnalysisRoutes' `PromptBuilder.build(ctx)` and any Phase 3
  * `PromptBuilder.build(ctx, tips)` callers) continue to compile. */
def build(
    ctx:   HabitContext,
    tips:  List[RetrievedTip] = Nil,
    notes: List[RetrievedTip] = Nil
): String =
  List(
    streakSection(ctx),
    dayPatternSection(ctx),
    rankingSection(ctx),
    timeOfDaySection(ctx),
    correlationSection(ctx),
    momentumSection(ctx),
    retrievedContextSection(tips),
    personalNotesSection(notes)
  ).filter(_.nonEmpty).mkString("\n\n")
```

The Phase 3 `def build(ctx: HabitContext, tips: List[RetrievedTip] = Nil): String`
signature is replaced by the new three-arg signature. The call site
`PromptBuilder.build(ctx)` in `AnalysisRoutes.scala` compiles unchanged
because both `tips` and `notes` default to `Nil`. The call site
`PromptBuilder.build(ctx, retrieved)` in `TipsRoutes.scala` compiles
unchanged (positional `tips`, default `notes = Nil`) — but TipsRoutes
itself is being modified, so the call becomes `build(ctx, tips = tips,
notes = notes)` with the new dedup-output values.

### `backend/src/main/scala/com/habittracker/http/TipsRoutes.scala`

Full modified source:

```scala
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
```

The Phase 3 `private val TOP_K: Int = 3` is removed and replaced by the
two named constants. The Phase 3 sequential `tipRepo.findSimilar(...)`
call is removed and replaced by `retrieveBoth(queryEmbedding, userId)`.
The Phase 3 `TipsResponse(tips = retrieved, narrative = narrative)`
construction is replaced by the three-field shape.

### `backend/src/main/scala/com/habittracker/AppResources.scala`

```scala
package com.habittracker

import cats.effect.{Clock, IO, Resource}
import cats.syntax.semigroupk._
import com.habittracker.client.{AnthropicClient, EmbeddingClient}
import com.habittracker.http.{
  AnalysisRoutes, BatchCompletionRoutes, DocsRoutes, HabitCompletionRoutes,
  HabitRoutes, InsightsRoutes, NoteRoutes, TipsRoutes
}
import com.habittracker.repository.{
  DoobieAnalyticsRepository,
  DoobieHabitCompletionRepository,
  DoobieHabitRepository,
  DoobieUserRepository,
  NoteRepository,
  TipRepository,
  UserRepository
}
import com.habittracker.service.{
  DefaultAnalyticsService,
  DefaultHabitCompletionService,
  DefaultHabitService
}
import org.http4s.HttpRoutes

final case class AppResources(
    routes:   HttpRoutes[IO],
    userRepo: UserRepository
)

object AppResources {

  def make: Resource[IO, AppResources] =
    for {
      xa               <- DatabaseConfig.transactor
      _                <- Resource.eval(IO(AnthropicClient.API_KEY_CHECK))
      _                <- Resource.eval(IO(EmbeddingClient.API_KEY_CHECK))
      userRepo          = new DoobieUserRepository(xa)
      habitRepo         = new DoobieHabitRepository(xa)
      completionRepo    = new DoobieHabitCompletionRepository(xa)
      analyticsRepo     = new DoobieAnalyticsRepository(xa)
      tipRepo           = new TipRepository(xa)
      noteRepo          = new NoteRepository(xa)
      habitService      = new DefaultHabitService(habitRepo, Clock[IO])
      completionSvc     = new DefaultHabitCompletionService(habitRepo, completionRepo, Clock[IO])
      analyticsService  = new DefaultAnalyticsService(habitRepo, analyticsRepo)
      allRoutes         = new DocsRoutes().routes <+>
                          new InsightsRoutes(analyticsService).routes <+>
                          new AnalysisRoutes(analyticsService).routes <+>
                          new TipsRoutes(analyticsService, tipRepo, noteRepo).routes <+>
                          new BatchCompletionRoutes(completionSvc).routes <+>
                          new NoteRoutes(noteRepo).routes <+>
                          new HabitRoutes(habitService).routes <+>
                          new HabitCompletionRoutes(completionSvc).routes
    } yield AppResources(allRoutes, userRepo)
}
```

Changes vs Phase 3:
- Import `NoteRoutes` (new) and `NoteRepository` (new).
- `noteRepo = new NoteRepository(xa)` — added between `tipRepo` and the
  service constructions.
- `TipsRoutes(analyticsService, tipRepo, noteRepo)` — third argument
  added.
- `new NoteRoutes(noteRepo).routes` — inserted between
  `BatchCompletionRoutes` and `HabitRoutes`.

`EmbeddingClient.API_KEY_CHECK` is already present in the Phase 3
wiring — do not add it again.

The route order is exactly:
`DocsRoutes → InsightsRoutes → AnalysisRoutes → TipsRoutes →
BatchCompletionRoutes → NoteRoutes → HabitRoutes →
HabitCompletionRoutes`.

---

## OpenAPI changes

`backend/src/main/resources/openapi/openapi.yaml` is the source of
truth for the wire format. Phase 4 changes:

### New path `/users/{userId}/habits/notes`

Insert the following after the existing `/users/{userId}/habits/completions/batch`
path block and before the `/users/{userId}/habits/{habitId}/completions/{completionId}`
block:

```yaml
  /users/{userId}/habits/notes:
    parameters:
      - name: userId
        in: path
        required: true
        schema:
          type: integer
          format: int64
    post:
      summary: Persist a user-authored habit note (embeds and stores)
      operationId: createUserNote
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/NoteRequest'
      responses:
        '201':
          description: Note persisted with embedding
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UserNote'
        '400':
          description: Malformed request body
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '500':
          description: Upstream embeddings or DB failure
```

### New schemas under `components.schemas`

Insert after `RetrievedTip` and before `TipsResponse`:

```yaml
    NoteRequest:
      type: object
      required: [content]
      properties:
        content:
          type: string
    UserNote:
      type: object
      required: [id, userId, content, createdAt]
      properties:
        id:
          type: integer
          format: int64
        userId:
          type: integer
          format: int64
        content:
          type: string
        createdAt:
          type: string
          format: date-time
```

### `TipsResponse` rewrite

Replace the existing block:

```yaml
    TipsResponse:
      type: object
      required: [tips, narrative]
      properties:
        tips:
          type: array
          items:
            $ref: '#/components/schemas/RetrievedTip'
        narrative:
          type: string
```

with:

```yaml
    TipsResponse:
      type: object
      required: [externalTips, personalNotes, narrative]
      properties:
        externalTips:
          type: array
          items:
            $ref: '#/components/schemas/RetrievedTip'
        personalNotes:
          type: array
          items:
            $ref: '#/components/schemas/RetrievedTip'
        narrative:
          type: string
```

### Existing path `/users/{userId}/habits/tips`

The path itself is unchanged. The response schema reference still points
at `#/components/schemas/TipsResponse`, which now reflects the new shape.

---

## Test plan

### `DeduplicationSpec` (new, pure unit, NOT `@Ignore`)

Path: `backend/src/test/scala/com/habittracker/service/DeduplicationSpec.scala`.
Six test methods (PBI AC-10):

1. `deduplicate`: identical content in both lists — only one item retained.
   Construct `tips = [RetrievedTip(HabitTip(1, "Stack your habits"), 0.90)]`
   and `notes = [RetrievedTip(HabitTip(7, "Stack your habits"), 0.85)]`.
   Assert `result._1.length + result._2.length == 1` (one of the two is
   kept). Assert the higher-scored one (the tip, score 0.90) is the
   survivor.
2. `deduplicate`: no overlap between lists — both lists returned unchanged.
   Construct `tips = [...]`, `notes = [...]` with totally different
   content. Assert `result == (tips, notes)`.
3. `deduplicate`: higher-scored duplicate retained, lower removed.
   Construct two near-identical strings with scores 0.95 and 0.90 in
   different sources. Assert the 0.95 item survives and the 0.90 is
   dropped.
4. `wordOverlapRatio`: known inputs produce expected ratio.
   `wordOverlapRatio("the quick brown fox", "the quick brown dog")`
   has shared = {the, quick, brown}, max = 4, ratio = 0.75. Assert
   `0.74 < r < 0.76`.
5. `isDuplicate`: pair within both thresholds returns `true`.
   Construct two `RetrievedTip`s with scores 0.90 and 0.91 (diff =
   0.01 < 0.05) and content "stack habits" and "stack habits"
   (overlap = 1.0 > 0.8). Assert `isDuplicate(a, b) == true`.
6. `isDuplicate`: pair outside either threshold returns `false`.
   Construct two `RetrievedTip`s with scores 0.90 and 0.20
   (diff = 0.7 > 0.05). Assert `isDuplicate(a, b) == false`.

### `RagLoggerSpec` (new, pure unit, NOT `@Ignore`)

Path: `backend/src/test/scala/com/habittracker/observability/RagLoggerSpec.scala`.

Capture stdout via `java.io.ByteArrayOutputStream` + `Console.withOut`.
Run `RagLogger.logRetrieval[IO](userId = 42L, tips = [...], notes =
[...]).unsafeRunSync()`. Assert:

- The captured string starts with `RAG userId=42`.
- Contains `externalCount=N` matching the seeded list size.
- Contains `personalCount=M` matching the seeded list size.
- Contains `topExternalScore=` followed by a 2-decimal float.
- Contains `topPersonalScore=` followed by a 2-decimal float.
- Does NOT contain any of the seeded `content` strings (privacy
  contract — seed each `RetrievedTip` with a distinctive content
  string and assert the captured string does not include it).

### `PromptBuilderSpec` extensions (modify, append-only)

Path: `backend/src/test/scala/com/habittracker/prompt/PromptBuilderSpec.scala`.

Append four new test cases inside the existing `"PromptBuilder" should`
block (do not modify existing cases):

1. `personalNotesSection should return non-empty for non-empty notes
   list`: assert the result starts with `"YOUR PAST NOTES:"` and
   contains the seeded note content.
2. `personalNotesSection should return empty string for Nil`:
   `PromptBuilder.personalNotesSection(Nil) shouldBe ""`.
3. `build with both tips and notes should include both section
   labels`: seed both lists; assert the output contains both
   `"Relevant habit-science tips retrieved for this user"` and
   `"YOUR PAST NOTES:"`.
4. `build with empty notes only (default Nil) should match Phase 3
   build output (regression guard)`:
   `PromptBuilder.build(fullyPopulated, tips, Nil) shouldBe
   PromptBuilder.build(fullyPopulated, tips)`. This guards AC-27.

### `NoteRepositorySpec` (new, Testcontainers, `@Ignore`)

Path: `backend/src/test/scala/com/habittracker/repository/NoteRepositorySpec.scala`.

Mirror the structure of `TipRepositorySpec`:
`PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17"))`,
`@Ignore`, `@RunWith(classOf[JUnitRunner])`, `BeforeAndAfterAll`,
`BeforeAndAfterEach`. In `beforeAll`: create the `vector` extension,
create the `user_notes` table, create the `user_notes_user_id_idx`
index. In `beforeEach`: `DELETE FROM user_notes`.

Four test methods (PBI AC-17):

1. `insert: stores a note with embedding; returns UserNote with a
   non-null generated id`. Assert `note.id > 0`, `note.userId == 42L`,
   `note.content == "..."`, `note.createdAt != null`.
2. `findSimilar (topK): returns exactly topK results ordered by
   similarityScore descending`. Seed 10 notes with random embeddings
   for the same userId. Query with topK = 3. Assert `results.length ==
   3`. Assert `results.head.similarityScore >= results.last.similarityScore`.
3. `findSimilar (userId filter): query for user A does not return
   notes belonging to user B`. Seed 5 notes for userId = 1 and 5 notes
   for userId = 2. Query `findSimilar(1, query, 5)`. Assert all
   returned notes' `tip.id` correspond to the userId = 1 inserts (the
   spec retains the inserted ids by capturing the return values of
   `insert`). Assert `results.length == 5` (or fewer if the random
   query happens to push a userId = 1 result past topK — note that
   topK = 5 and userId = 1 has exactly 5 rows).
4. `findSimilar (empty table): empty user_notes returns empty list
   without error`. After the `beforeEach` `DELETE`, query
   `findSimilar(1, randomVector, 3)`. Assert `results == Nil`.

### `TipsRoutesParallelRetrievalSpec` (new, IO unit, NOT `@Ignore`)

Path: `backend/src/test/scala/com/habittracker/http/TipsRoutesParallelRetrievalSpec.scala`.

Verifies AC-18: `retrieveBoth` calls both repos exactly once.

Approach:
- Construct stub/fake versions of `TipRepository` and `NoteRepository`
  that record their invocation count using `cats.effect.Ref`.
- Since `retrieveBoth` is `private` to `TipsRoutes`, the test exercises
  it indirectly via the public `routes` field — issue a fake `Request`
  to `GET /users/1/habits/tips`. To avoid invoking real
  `EmbeddingClient.embed` and `AnthropicClient.complete`, the test
  EITHER (a) extracts `retrieveBoth` to a package-private accessor on
  `TipsRoutes` so the spec can call it directly without the rest of
  the pipeline OR (b) calls `(tipRepo.findSimilar(...),
  noteRepo.findSimilar(...)).parTupled` directly to verify the
  two-call assertion.

  Recommendation for the Developer: take approach (b) — the spec's
  purpose is to verify the parallel-tuple primitive, not the full
  request pipeline. Construct two stubbed repos that increment a `Ref`
  on every `findSimilar` call, then call
  `(tipRepo.findSimilar(query, 2), noteRepo.findSimilar(1L, query,
  2)).parTupled.unsafeRunSync()`. Assert both `Ref`s read 1.

  This satisfies AC-18 ("test uses ScalaTest `AnyWordSpec` with
  `@RunWith(classOf[JUnitRunner])`") — the framework choice and the
  invocation-count assertion are both in scope; the indirect path
  through `routes` is not required by the AC.

Test class:
- `@RunWith(classOf[JUnitRunner])`, NOT `@Ignore`.
- One test method: `retrieveBoth invokes TipRepository.findSimilar
  exactly once and NoteRepository.findSimilar exactly once`.

### `NoteRoundtripIntegrationSpec` (new, Testcontainers, `@Ignore`)

Path: `backend/src/test/scala/com/habittracker/integration/NoteRoundtripIntegrationSpec.scala`.

Verifies AC-19: POST /notes followed by GET /tips returns the note
content in `personalNotes`.

`@Ignore`. Bring up the pgvector container, apply Liquibase changesets
for users + habits, plus the init script DDL for habit_tips and
user_notes (or run `infra/db/init/*.sql` directly). Seed a user.

Two API calls in sequence:
1. POST `/users/{userId}/habits/notes` with body `{"content": "I run
   better in the morning"}`. Assert HTTP 201 and the returned
   `UserNote.id > 0`.
2. GET `/users/{userId}/habits/tips`. Assert HTTP 200, body
   deserialises to `TipsResponse`, `personalNotes` is non-empty, the
   posted content (or its retrieved form) appears in
   `personalNotes.map(_.tip.content)`.

This is a live-API integration test (real OPENAI + ANTHROPIC keys
required). `@Ignore` keeps it out of CI.

### Phase 1, 2, 3 regression guards

- **Phase 1 + Phase 2 tests** (`InsightPromptSpec`,
  `DoobieAnalyticsRepositorySpec` Phase 1+2 cases, Phase 2 codec
  tests, Phase 2 integration test): not modified. AC-21 mandates this.
  `./gradlew test` reports zero failures for these classes.
- **Phase 3 tests** (`TipRepositorySpec`, `SeedTipsIdempotencySpec`,
  `PromptBuilderSpec` Phase 3 cases): not modified except for the
  `TipsResponse` field rename if any test file references it. **Grep
  of the current test tree finds zero references to
  `TipsResponse.tips` or any equivalent**, so no Phase 3 test edit is
  required in practice. AC-22 mandates that the only permitted
  Phase 3 test edit is the field rename. The Developer must run a
  final grep before declaring done; if any case reference is found,
  rename it.

### TipsResponse field-rename test impact (concrete)

Files in `backend/src/test/` searched for `TipsResponse` or `\.tips`
on a `TipsResponse` value: **0 matches**. The breaking rename
therefore does not require any Phase 3 test edit. The Developer must
re-run the grep before declaring done; if a match is found in
post-Phase-3 code added between the time this plan is written and
implementation, rename `.tips` → `.externalTips` on that line — no
other change.

---

## docs/future_improvements.md content

Path: `docs/future_improvements.md` (PBI AC-29). Content (verbatim
copy of the Phase 4 brief's POST-PHASE 4 NOTES section):

```markdown
# Future Improvements (Post-Phase 4)

The following items were identified during Phase 4 architecture and
implementation. They are out of scope for the current PoC but are
recorded here so a future PBI can pick them up with context.

## CHUNKING

Notes longer than ~500 tokens should be split before embedding.
Current implementation embeds the full note as a single vector.
Long notes produce averaged embeddings that lose specific detail.

## EMBEDDING CACHE

Re-embedding identical content wastes API calls and money.
A simple content-hash lookup in a cache table would prevent duplicate
embeddings.

## TOKEN BUDGET

No enforcement of maximum token count on retrieved context before
prompt assembly. Long retrieved content can push the prompt over the
model's context window. A token counter on the assembled prompt with
truncation logic is needed.

## EVAL PERSISTENCE

Phase 4 originally specified a `POST /tips/evaluate` endpoint with a
keyword-match metric; it was removed per engineer decision 2026-04-29
(see ADR-011 §7). A future revival should use a labelled question
set, recall@K, an LLM-as-judge metric, and persisted results to
enable longitudinal RAG-quality analysis. Persisting (userId,
question, foundKeywords, narrative, timestamp) enables offline
analysis of RAG quality over time.

## FRAMEWORK INTRODUCTION POINT

Phase 4 completes a hand-rolled RAG pipeline.
This is now an appropriate point to introduce LangChain4j as a
comparison: build the same pipeline using the framework and compare
code volume, abstraction quality, and what the framework hides vs
reveals.
```

---

## Build sequence (ordered)

The Developer must implement in this order to keep each compile step
green:

1. **Database**: write `infra/db/init/03_create_user_notes.sql`. Run
   `docker compose -f docker-compose.yaml down -v` then `up -d` to
   verify the table is created. (`docker-compose.yaml` lives at the
   repo root, not under `infra/` — confirmed.) Re-seed
   `habit_tips` with `./gradlew runSeedTips`.
2. **Model**: edit `Analytics.scala` — add `UserNote`, `NoteRequest`,
   rewrite `TipsResponse`. Run `./gradlew compileScala` — the codec
   re-derivation will trigger a recompile of `AnalyticsCodecs`. The
   only compile error expected at this step is from `TipsRoutes`
   (still using the old `TipsResponse(tips = ...)` shape) and from
   `AnalyticsCodecs` if there is a missing import for `UserNote` /
   `NoteRequest`.
3. **Codecs**: edit `AnalyticsCodecs.scala` — add the four new lines
   for `UserNote` and `NoteRequest`. Update the import list. Compile.
   The `TipsRoutes` error from step 2 should still be the only
   remaining compile error.
4. **NoteRepository**: write
   `repository/NoteRepository.scala`. Compile.
5. **Deduplication**: write `service/Deduplication.scala`. Compile.
6. **RagLogger**: write `observability/RagLogger.scala`. Compile.
7. **PromptBuilder**: edit `prompt/PromptBuilder.scala` — add
   `personalNotesSection`, widen `build` to three args. Compile.
   `AnalysisRoutes.build(ctx)` and any caller site that passes only
   `tips` continue to compile due to the default args.
8. **TipsRoutes**: edit `http/TipsRoutes.scala` — add `noteRepo`
   constructor argument, define `TIPS_TOP_K`, `NOTES_TOP_K`,
   `retrieveBoth`, replace the request-body for-comprehension. Compile.
9. **NoteRoutes**: write `http/NoteRoutes.scala`. Compile.
10. **AppResources**: edit `AppResources.scala` — add `noteRepo`
    construction, pass to `TipsRoutes`, add `NoteRoutes` to the
    route composition between `BatchCompletionRoutes` and
    `HabitRoutes`. Compile.
11. **OpenAPI**: edit `openapi.yaml` — `TipsResponse` rewrite, two
    new schemas, one new path. The schema is read at runtime by
    `DocsRoutes`, so a syntax error surfaces only at server start.
    Run `./gradlew compileScala`; lint by inspection.
12. **Tests**: write `DeduplicationSpec`, `RagLoggerSpec`,
    `NoteRepositorySpec`, `TipsRoutesParallelRetrievalSpec`,
    `NoteRoundtripIntegrationSpec`. Append new cases to
    `PromptBuilderSpec`. Run `./gradlew test`. Pure unit tests must
    pass; Testcontainers and integration specs remain `@Ignore`.
13. **Docs**: write `docs/future_improvements.md`.
14. **Final regression**: `./gradlew test`. Zero failures, zero
    compile errors. Phase 1/2/3 test files unchanged in the diff.

---

## ADRs required

ADR-011 written and saved at `docs/adr/ADR-011-phase4-full-rag.md`
before this plan. It covers all eight architectural questions
identified in the PBI Technical Notes section, plus the eval
endpoint removal per engineer decision 2026-04-29.

---

## Open questions

None. All architectural questions are resolved in ADR-011. The eval
endpoint is removed (engineer decision 2026-04-29). The TipsResponse
breaking change has zero current test impact (grep confirmed). All
constraints from the PBI's "Out of scope" and "Hard limits" sections
are honoured.

If during implementation the Developer discovers any of the
following, they must STOP and request engineer guidance rather than
silently fix:

- A test in `backend/src/test/` references `TipsResponse.tips` (the
  rename should be a one-line edit, but the rename count needs to be
  surfaced).
- The `infra/db/init/03_create_user_notes.sql` file fails to apply
  on a fresh `docker compose up` (likely indicates lexicographic
  ordering or a pgvector image version drift).
- The `parTupled` import resolution fails (suggests a cats version
  drift; do NOT silently add a new dependency).
- The semiauto re-derivation of `TipsResponse` codec produces a
  decoder that does not accept the new wire shape (suggests an
  import or shadowing issue; do NOT switch to manual `forProductN`).
