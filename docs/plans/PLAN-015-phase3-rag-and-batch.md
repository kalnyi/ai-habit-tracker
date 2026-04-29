# PLAN-015: Phase 3 — Basic RAG (Tips Endpoint) + Batch Completions + UNIQUE Constraint Verification

## PBI reference
- PBI-015: Phase 3 — Basic RAG: Personalised Tips Endpoint
- PBI-016: Batch Habit Completions Endpoint (Backfill)
- PBI-017: One Completion Per Habit Per Day — Constraint Verification
- Phase brief: `docs/phases/phase_3_basic_rag.md`
- ADRs: ADR-008 (Phase 1), ADR-009 (Phase 2), ADR-010 (this phase)

## Summary
Three streams in one phase. **Stream A (PBI-015)** introduces the first end-to-end
RAG pipeline: a pgvector-backed `habit_tips` corpus seeded once via a new
`SeedTips` `IOApp`, an `EmbeddingClient` mirroring `AnthropicClient`, a
`TipRepository` with a named cosine-similarity SQL fragment, an extension to
`PromptBuilder` (`retrievedContextSection` + `build` signature widened with a
default), a new `buildTipsQuery` method on `AnalyticsService`, and a new
`TipsRoutes` class wired into `AppResources` between `AnalysisRoutes` and the
new `BatchCompletionRoutes`. **Stream B (PBI-016)** adds a partial-success
batch endpoint at `POST /users/{userId}/habits/completions/batch` with two new
DTOs and one new service method, processing each item in its own transaction
via the existing `DoobieHabitCompletionRepository.create` chain. **Stream C
(PBI-017)** is verification-only: two integration tests confirm the
already-wired UNIQUE-constraint → `ConflictError` → 409/skipped chain. No new
Liquibase migration is created. ADR-010 captures all twelve architectural
decisions; this plan locks the implementation order, the exact source for the
load-bearing files, and the OpenAPI diff.

## Preconditions / notes to the Developer agent

- **Read ADR-010 in full** before writing any file in this plan. Twelve
  decisions are locked there: pgvector init-script boundary, EmbeddingClient
  shape, TipRepository `IO` contract, named similarity SQL, pgvector textual
  literal (no new dependency), SeedTips Gradle wiring, `retrievedTips`
  default, tips endpoint composition, batch endpoint partial-success contract,
  PBI-017 verification-only, OpenAPI updates, build tool unchanged.
- **No new runtime or test dependency is added.** Hand-roll the pgvector
  textual literal helper (ADR-010 §5). Do not add `com.pgvector:pgvector-java`
  unless the engineer overrides this in review.
- **Build tool is Gradle.** Phase brief says "sbt" — documentation drift.
  Use `./gradlew compileScala`, `./gradlew test`, `./gradlew runSeedTips`.
- **HARD LIMITS — files that must remain byte-for-byte identical:**
  `AnthropicClient.scala`, `InsightPrompt.scala`, `InsightsRoutes.scala`,
  `AnalysisRoutes.scala`, the existing `HabitCompletionRoutes` (the single
  POST/GET/DELETE patterns), all Phase 1 and Phase 2 test sources, and
  Liquibase changesets 001–005. Verify with
  `git diff main -- <path>` producing no output for each.
- **Routes use `IO` directly.** No `F[_]: Async` on `TipsRoutes`,
  `BatchCompletionRoutes`, `TipRepository`, or any new service method.
  `EmbeddingClient.embed[F[_]: Async]` is the only `F[_]` signature in this
  plan; it is always called as `EmbeddingClient.embed[IO](...)`.
- **Codecs use `io.circe.generic.semiauto._`.** Match
  `AnalyticsCodecs.scala` and `CompletionCodecs.scala`. Do not introduce
  `io.circe.generic.auto`.
- **Tests use ScalaTest `AnyWordSpec` + `@RunWith(classOf[JUnitRunner])`.**
  Testcontainers specs carry a class-level `@Ignore` plus a
  `// requires Docker - run manually` comment, per the Phase 1/2 convention.
- **Inline comments in four required locations are mandatory** — they are
  reviewed for genuine prose explanations, not code restatement:
  1. `EmbeddingClient.scala`, above the `MODEL` constant — what an embedding
     vector is (1536 numbers representing semantic meaning, similar texts
     produce vectors close together).
  2. `TipRepository.scala`, above `similaritySearchSql` — cosine similarity
     in plain terms (1.0 identical, 0.0 unrelated; `<=>` is distance, subtract
     from 1).
  3. `TipRepository.scala`, above `findSimilar` — why ORDER BY distance
     ascending gives semantic similarity descending.
  4. `PromptBuilder.scala`, above `retrievedContextSection` — what
     "grounding" means in RAG (anchoring the LLM in retrieved external
     knowledge versus a cold prompt).

## Build sequence (ordered — keeps the compiler green between commits)

1. **Infrastructure first** — verify Docker Compose restarts cleanly.
   - Add `infra/db/init/02_create_habit_tips.sql`.
   - Run `docker compose down -v && docker compose up -d`.
   - Verify with `psql` that `pg_extension` contains `vector` and
     `habit_tips` exists with the expected columns.
2. **Domain types** — `model/Analytics.scala`. Add `HabitTip`, `RetrievedTip`,
   `TipsResponse`. Append `retrievedTips: List[String] = Nil` to
   `HabitContext`. Compile: `./gradlew compileScala`.
3. **Codecs** — extend `AnalyticsCodecs.scala` with `HabitTip`,
   `RetrievedTip`, `TipsResponse` semiauto codecs.
   `HabitContext`/`InsightResponse`/`AnalysisResponse` codecs re-derive
   automatically when their case classes change.
4. **EmbeddingClient** — `client/EmbeddingClient.scala`. Compile.
5. **TipRepository** — `repository/TipRepository.scala`. Compile.
6. **PromptBuilder extension** — add `retrievedContextSection`, widen
   `build` signature with default `Nil`. Compile (the existing
   `AnalysisRoutes.scala` and `InsightsRoutes.scala` call sites must still
   compile — they do, because `build(ctx)` uses the default).
7. **AnalyticsService extension** — add `buildTipsQuery(ctx): String` to the
   trait and `DefaultAnalyticsService`. Compile.
8. **TipsRoutes** — `http/TipsRoutes.scala`. Compile.
9. **Batch DTOs** — `http/dto/BatchCompletionResponse.scala`,
   `http/dto/SkippedCompletion.scala`.
10. **Batch codecs** — extend `CompletionCodecs.scala`.
11. **Batch service method** — extend `HabitCompletionService` trait and
    `DefaultHabitCompletionService` with `recordCompletionBatch`. Compile.
12. **BatchCompletionRoutes** — `http/BatchCompletionRoutes.scala`. Compile.
13. **AppResources wiring** — register `TipsRoutes` after `AnalysisRoutes`
    and `BatchCompletionRoutes` between `TipsRoutes` and `HabitRoutes`. Add
    `Resource.eval(IO(EmbeddingClient.API_KEY_CHECK))` after the existing
    Anthropic check. Construct `tipRepo` from the existing transactor.
    Compile.
14. **SeedTips script + Gradle task** — `scripts/SeedTips.scala`,
    `resources/habit_tips.txt`, `runSeedTips` `JavaExec` task in
    `build.gradle`. Compile.
15. **OpenAPI** — extend `openapi.yaml` per "OpenAPI changes" below.
16. **Tests** — new `TipRepositorySpec`, new `EmbeddingClientSpec` is *not*
    required (manual live check only), extend `PromptBuilderSpec` with four
    new cases, new `BatchCompletionRoutesSpec` (or extend the integration
    spec) with the partial-success scenario, add the duplicate-409 and
    duplicate-batch-skip integration cases, plus the SeedTips idempotency
    case. Run `./gradlew test`.
17. **Manual verification** — run `docker compose up -d`, `./gradlew update`
    (Liquibase), `./gradlew runSeedTips` (twice — second run logs only
    "Skipped (already exists)" lines), then `./gradlew run` and
    `curl -s http://localhost:8080/users/1/habits/tips` (after creating a
    user and at least one habit). Then run a four-item batch completion
    request (two valid, one duplicate, one bad habitId) against
    `/users/{userId}/habits/completions/batch` and confirm
    `inserted.length == 2`, `skipped.length == 2`.

## Affected files

| File | Change type | Description |
|---|---|---|
| `infra/db/init/02_create_habit_tips.sql` | Create | Creates `habit_tips` table with `vector(1536)` column and `md5(content)` unique index. |
| `backend/src/main/scala/com/habittracker/model/Analytics.scala` | Modify | Add `HabitTip`, `RetrievedTip`, `TipsResponse`; append `retrievedTips: List[String] = Nil` to `HabitContext`. |
| `backend/src/main/scala/com/habittracker/http/AnalyticsCodecs.scala` | Modify | Add semiauto encoders/decoders for `HabitTip`, `RetrievedTip`, `TipsResponse`. |
| `backend/src/main/scala/com/habittracker/client/EmbeddingClient.scala` | Create | Direct sttp call to OpenAI embeddings, mirrors `AnthropicClient` shape. |
| `backend/src/main/scala/com/habittracker/repository/TipRepository.scala` | Create | Doobie repository with `insert` and `findSimilar(queryEmbedding, topK)`. |
| `backend/src/main/scala/com/habittracker/prompt/PromptBuilder.scala` | Modify | Add `retrievedContextSection`; widen `build` to `(ctx, tips: List[RetrievedTip] = Nil)`. |
| `backend/src/main/scala/com/habittracker/service/AnalyticsService.scala` | Modify | Add `buildTipsQuery(ctx: HabitContext): String` to trait and `DefaultAnalyticsService`. |
| `backend/src/main/scala/com/habittracker/http/TipsRoutes.scala` | Create | Handles `GET /users/{userId}/habits/tips`. Defines `TOP_K = 3`. |
| `backend/src/main/scala/com/habittracker/http/dto/BatchCompletionResponse.scala` | Create | Response DTO `(inserted, skipped)`. |
| `backend/src/main/scala/com/habittracker/http/dto/SkippedCompletion.scala` | Create | DTO `(habitId, completedOn, reason)`. |
| `backend/src/main/scala/com/habittracker/http/CompletionCodecs.scala` | Modify | Add semiauto codecs for the two batch DTOs. |
| `backend/src/main/scala/com/habittracker/service/HabitCompletionService.scala` | Modify | Add `recordCompletionBatch` to trait and `DefaultHabitCompletionService`. |
| `backend/src/main/scala/com/habittracker/http/BatchCompletionRoutes.scala` | Create | Handles `POST /users/{userId}/habits/completions/batch`. |
| `backend/src/main/scala/com/habittracker/AppResources.scala` | Modify | Add `EmbeddingClient.API_KEY_CHECK`, construct `tipRepo`, register `TipsRoutes` and `BatchCompletionRoutes` in route chain. |
| `backend/src/main/scala/com/habittracker/scripts/SeedTips.scala` | Create | `IOApp.Simple` that reads `habit_tips.txt`, embeds each line, inserts if absent. |
| `backend/src/main/resources/habit_tips.txt` | Create | ≥ 25 tips, one per line, covering 10 topic areas listed in PBI-015. |
| `backend/build.gradle` | Modify | Add `runSeedTips` `JavaExec` task. No new dependencies. |
| `backend/src/main/resources/openapi/openapi.yaml` | Modify | Add `retrievedTips` to `HabitContext`; add `HabitTip`, `RetrievedTip`, `TipsResponse`, `BatchCompletionResponse`, `SkippedCompletion` schemas; add two new path entries. |
| `backend/src/test/scala/com/habittracker/repository/TipRepositorySpec.scala` | Create | Testcontainers spec with four cases. `@Ignore` + `// requires Docker - run manually`. |
| `backend/src/test/scala/com/habittracker/prompt/PromptBuilderSpec.scala` | Modify | Append four new test cases for `retrievedContextSection` and the widened `build`. |
| `backend/src/test/scala/com/habittracker/service/HabitCompletionServiceSpec.scala` | Modify | Append a partial-success unit test using the existing in-memory repository double. |
| `backend/src/test/scala/com/habittracker/integration/HabitCompletionApiIntegrationSpec.scala` | Modify | Append two integration cases: duplicate-409 single endpoint, batch endpoint partial success. `@Ignore` retained. |
| `backend/src/test/scala/com/habittracker/scripts/SeedTipsIdempotencySpec.scala` | Create | Testcontainers spec running `SeedTips.run` twice. `@Ignore` + Docker comment. |

## New components

### `com.habittracker.model.HabitTip`, `RetrievedTip`, `TipsResponse`
Three case classes appended to `Analytics.scala`. `HabitContext` gains an
eighth field with default `Nil`.

### `com.habittracker.client.EmbeddingClient`
Scala `object` with no trait, no constructor. Constants `MODEL`, `API_URL`,
`DIMENSION`. `private val API_KEY` resolved at object-init from
`OPENAI_API_KEY`. `val API_KEY_CHECK: Unit`. `def embed[F[_]: Async](text: String): F[Vector[Float]]`.

### `com.habittracker.repository.TipRepository`
Class `(transactor: Transactor[IO])`. No trait. Methods:
- `def insert(content: String, embedding: Vector[Float]): IO[HabitTip]`
- `def findExistingByContent(content: String): IO[Option[HabitTip]]`
  (used by `SeedTips` for idempotency)
- `def findSimilar(queryEmbedding: Vector[Float], topK: Int): IO[List[RetrievedTip]]`
- `private def similaritySearchSql(queryEmbedding: Vector[Float], topK: Int): Query0[(Long, String, Double)]`
- `private def embeddingToPgLiteral(v: Vector[Float]): String`

### `com.habittracker.prompt.PromptBuilder` (extension)
- `def retrievedContextSection(tips: List[RetrievedTip]): String`
- `def build(ctx: HabitContext, tips: List[RetrievedTip] = Nil): String`
  (signature widened, body appends `retrievedContextSection(tips)` to the
  filtered list)

### `com.habittracker.service.AnalyticsService` (extension)
- `def buildTipsQuery(ctx: HabitContext): String` (pure)

### `com.habittracker.http.TipsRoutes`
Class `(service: AnalyticsService, tipRepo: TipRepository)`.
Defines `private val TOP_K: Int = 3`. Single route
`GET /users/{userId}/habits/tips`.

### `com.habittracker.http.dto.BatchCompletionResponse`
Case class `(inserted: List[HabitCompletionResponse], skipped: List[SkippedCompletion])`.

### `com.habittracker.http.dto.SkippedCompletion`
Case class `(habitId: UUID, completedOn: LocalDate, reason: String)`.

### `com.habittracker.service.HabitCompletionService` (extension)
- `def recordCompletionBatch(userId: Long, items: List[CreateHabitCompletionRequest]): IO[BatchCompletionResponse]`

### `com.habittracker.http.BatchCompletionRoutes`
Class `(service: HabitCompletionService)`. Single route
`POST /users/{userId}/habits/completions/batch`.

### `com.habittracker.scripts.SeedTips`
`IOApp.Simple`. Reads `habit_tips.txt` from the classpath, iterates lines,
calls `tipRepo.findExistingByContent` then `EmbeddingClient.embed` +
`tipRepo.insert` per non-duplicate line. Logs progress.

## API contract

### `GET /users/{userId}/habits/tips`

- Path parameters: `userId` (Long).
- Request body: none.
- Response 200: `application/json`, schema `TipsResponse`:
  ```json
  {
    "tips": [
      {
        "tip": { "id": 12, "content": "Stack a new habit on top of an existing one." },
        "similarityScore": 0.83
      }
    ],
    "narrative": "..."
  }
  ```
- Response 500: upstream LLM or embeddings failure (existing
  `ErrorHandler` propagates as IO failure → 500 by http4s default).

### `POST /users/{userId}/habits/completions/batch`

- Path parameters: `userId` (Long).
- Request body: JSON array of `CreateHabitCompletionRequest` items, each
  with `habitId` (UUID), `completedOn` (date), optional `note` and
  `completedAt` (date-time).

  > **Note on request shape vs. existing single-completion request.** The
  > existing `CreateHabitCompletionRequest` does not carry `habitId`
  > because the single endpoint takes it from the path. The batch
  > endpoint extends the in-flight DTO at the route layer: the route
  > deserialises the request as
  > `List[BatchCompletionItem]` where
  > `BatchCompletionItem(habitId, completedOn, note, completedAt)` is a
  > new request DTO whose four fields wrap a `CreateHabitCompletionRequest`
  > plus the `habitId`. This is the cleanest place for the additional
  > field. The service method then reconstructs
  > `CreateHabitCompletionRequest(completedOn, note, completedAt)` per
  > item and calls the existing single-item logic.

  Add: `backend/src/main/scala/com/habittracker/http/dto/BatchCompletionItem.scala`:
  ```scala
  package com.habittracker.http.dto
  import java.time.{Instant, LocalDate}
  import java.util.UUID
  final case class BatchCompletionItem(
      habitId:     UUID,
      completedOn: LocalDate,
      note:        Option[String],
      completedAt: Option[Instant]
  )
  ```
  Decoder is added to `CompletionCodecs` via semiauto.

- Response 200: `application/json`, schema `BatchCompletionResponse`:
  ```json
  {
    "inserted": [ { "id": "...", "habitId": "...", "completedOn": "2026-04-01", "note": null, "createdAt": "...", "completedAt": null } ],
    "skipped":  [ { "habitId": "...", "completedOn": "2026-04-01", "reason": "duplicate: ..." } ]
  }
  ```
  HTTP 200 even when `inserted` is empty.
- Response 400: malformed JSON body. `ErrorResponse(message)`.

## Database changes

**No new Liquibase migration.** The `habit_tips` table is created by an init
script (ADR-010 §1):

`infra/db/init/02_create_habit_tips.sql`:

```sql
-- Run once on first container startup
-- Creates the corpus table for pgvector cosine-similarity retrieval
-- See ADR-010.

CREATE TABLE IF NOT EXISTS habit_tips (
    id        BIGSERIAL    PRIMARY KEY,
    content   TEXT         NOT NULL,
    embedding vector(1536) NOT NULL
);

-- md5 hash index because pgvector tip strings can exceed the B-tree
-- size limit. SeedTips also performs a SELECT-then-INSERT idempotency
-- check, so this is a defensive layer.
CREATE UNIQUE INDEX IF NOT EXISTS uq_habit_tips_content
    ON habit_tips (md5(content));
```

The existing `infra/db/init/01_enable_vector.sql` (already present) ensures
`CREATE EXTENSION IF NOT EXISTS vector` runs first.

The UNIQUE constraint `uq_habit_completions_habit_day` already exists in
migration 002 — see ADR-010 §10 and PBI-017. **No migration 006.**

## LLM integration

### Use case 1 — daily personalised tip (PBI-015)
This phase implements the **daily tip** LLM use case from CLAUDE.md.

- Prompt files: extends existing `PromptBuilder.HABIT_COACH_SYSTEM_PROMPT`
  (no new file). The new section is `retrievedContextSection(tips)` inside
  `PromptBuilder.scala`.
- Embedding model: `text-embedding-3-small` (1536 dimensions), via OpenAI
  `/v1/embeddings`. Engineer's choice; documented in ADR-010 §2.
- RAG strategy:
  1. `service.buildHabitContext(userId)` assembles the eight-field
     `HabitContext`.
  2. `service.buildTipsQuery(ctx)` derives a single plain-text query from
     `ctx`. Implementation: concatenates the top-3 ranked habit names, the
     two worst-performing days of the week, and a sentence template:
     `"Habits I'm working on: $top3. I struggle most on $worst1 and $worst2.
     What practical advice helps?"`. Empty `consistencyRanking` or
     `completionByDay` falls back to a static neutral query
     `"General habit-building practical advice."`.
  3. `EmbeddingClient.embed[IO](query)` returns the query embedding.
  4. `tipRepo.findSimilar(queryEmbedding, TOP_K)` returns top-3 by cosine
     similarity.
  5. `PromptBuilder.build(ctx, retrieved)` produces the seven-section
     prompt (six analysis sections + retrieved-context section).
  6. `AnthropicClient.complete[IO](HABIT_COACH_SYSTEM_PROMPT, prompt)`
     returns the narrative.
- Prompt versioning: `HABIT_COACH_SYSTEM_PROMPT` is unchanged. The
  retrieved-context section text uses a stable preamble:
  `"Relevant habit-science tips retrieved for this user (use as supporting
  evidence, not verbatim):\n- ...".`. Changes to this preamble require an
  entry in `docs/prompt-changelog.md`.

## SeedTips Gradle task

`backend/build.gradle` gains one block (between the existing `update` task
and the `tasks.withType(ScalaCompile)` block):

```gradle
// ---------------------------------------------------------------------------
// SeedTips runner — embeds and inserts the habit-tips corpus into pgvector.
// Usage: ./gradlew runSeedTips
// Requires: OPENAI_API_KEY in env, Postgres up, habit_tips table exists.
// ---------------------------------------------------------------------------
tasks.register('runSeedTips', JavaExec) {
    group         = 'application'
    description   = 'Embeds and inserts the habit-tips corpus into the habit_tips table.'
    classpath     = sourceSets.main.runtimeClasspath
    mainClass     = 'com.habittracker.scripts.SeedTips'
    standardInput = System.in
    dependsOn     = ['classes']
}
```

No `application.mainClass` change. The existing `run` task continues to
launch `Main`.

## EmbeddingClient — full source

`backend/src/main/scala/com/habittracker/client/EmbeddingClient.scala`:

```scala
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
        .header("content-type", "application/json")
        .body(bodyJson)
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
```

## TipRepository — full source

`backend/src/main/scala/com/habittracker/repository/TipRepository.scala`:

```scala
package com.habittracker.repository

import cats.effect.IO
import com.habittracker.model.{HabitTip, RetrievedTip}
import doobie._
import doobie.implicits._

final class TipRepository(transactor: Transactor[IO]) {

  // -------------------------------------------------------------------------
  // pgvector wire-format helper
  //
  // pgvector accepts vector literals in the textual form `[v1,v2,...]`.
  // Doobie has no built-in Meta[Vector[Float]] (and we deliberately do not
  // pull in com.pgvector:pgvector-java per ADR-010 §5), so we render the
  // vector to its literal form and cast it back to `vector` in SQL via
  // `::vector`. This keeps the wire format explicit and reviewable.
  // -------------------------------------------------------------------------
  private def embeddingToPgLiteral(v: Vector[Float]): String =
    v.mkString("[", ",", "]")

  // -------------------------------------------------------------------------
  // SQL queries
  // -------------------------------------------------------------------------

  private def insertSql(content: String, embeddingLiteral: String): Update0 =
    sql"""
      INSERT INTO habit_tips (content, embedding)
      VALUES ($content, $embeddingLiteral::vector)
      RETURNING id
    """.update

  private def findByContentSql(content: String): Query0[(Long, String)] =
    sql"""
      SELECT id, content
      FROM habit_tips
      WHERE content = $content
      LIMIT 1
    """.query[(Long, String)]

  // Cosine similarity measures the angle between two embedding vectors.
  // A score of 1.0 means the vectors point in exactly the same direction
  // (identical meaning); 0.0 means they are orthogonal (unrelated meaning).
  // pgvector's `<=>` operator returns *cosine distance*, defined as
  // 1 - cosineSimilarity. We subtract from 1 to translate distance back
  // into the more intuitive similarity score before returning it to the
  // caller.
  private def similaritySearchSql(
      queryEmbedding: Vector[Float],
      topK:           Int
  ): Query0[(Long, String, Double)] = {
    val embeddingLiteral: String = embeddingToPgLiteral(queryEmbedding)
    sql"""
      SELECT id,
             content,
             1.0 - (embedding <=> $embeddingLiteral::vector) AS score
      FROM habit_tips
      ORDER BY embedding <=> $embeddingLiteral::vector
      LIMIT $topK
    """.query[(Long, String, Double)]
  }

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  def insert(content: String, embedding: Vector[Float]): IO[HabitTip] = {
    val literal = embeddingToPgLiteral(embedding)
    insertSql(content, literal)
      .withUniqueGeneratedKeys[Long]("id")
      .transact(transactor)
      .map(id => HabitTip(id, content))
  }

  def findExistingByContent(content: String): IO[Option[HabitTip]] =
    findByContentSql(content).option.transact(transactor).map {
      _.map { case (id, c) => HabitTip(id, c) }
    }

  // pgvector's `<=>` operator returns cosine *distance*, where smaller is
  // closer. Ordering ascending by `<=>` therefore returns rows from most
  // similar (smallest distance) to least similar (largest distance). The
  // caller sees results sorted by semantic similarity descending — which is
  // what RAG retrieval needs.
  def findSimilar(
      queryEmbedding: Vector[Float],
      topK:           Int
  ): IO[List[RetrievedTip]] =
    similaritySearchSql(queryEmbedding, topK)
      .to[List]
      .transact(transactor)
      .map(_.map { case (id, content, score) =>
        RetrievedTip(HabitTip(id, content), score)
      })
}
```

## BatchCompletionRoutes — full source

`backend/src/main/scala/com/habittracker/http/BatchCompletionRoutes.scala`:

```scala
package com.habittracker.http

import cats.effect.IO
import com.habittracker.http.CompletionCodecs._
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
```

`recordCompletionBatch` signature on `HabitCompletionService`:

```scala
def recordCompletionBatch(
    userId: Long,
    items:  List[BatchCompletionItem]
): IO[BatchCompletionResponse]
```

`DefaultHabitCompletionService.recordCompletionBatch` body (sketch — full
implementation by the Developer):

```scala
override def recordCompletionBatch(
    userId: Long,
    items:  List[BatchCompletionItem]
): IO[BatchCompletionResponse] =
  items.traverse(processOne(userId, _)).map { results =>
    val inserted = results.collect { case Right(r) => r }
    val skipped  = results.collect { case Left(s)  => s }
    BatchCompletionResponse(inserted, skipped)
  }

private def processOne(
    userId: Long,
    item:   BatchCompletionItem
): IO[Either[SkippedCompletion, HabitCompletionResponse]] =
  habitRepo.findActiveById(userId, item.habitId).flatMap {
    case None =>
      IO.pure(Left(SkippedCompletion(
        habitId     = item.habitId,
        completedOn = item.completedOn,
        reason      = "habit not found or not active for this user"
      )))
    case Some(_) =>
      for {
        now <- clock.realTimeInstant
        id  <- IO(UUID.randomUUID())
        completion = HabitCompletion(
          id          = id,
          habitId     = item.habitId,
          completedOn = item.completedOn,
          note        = item.note,
          createdAt   = now,
          completedAt = item.completedAt
        )
        result <- completionRepo.create(completion)
      } yield result match {
        case Right(()) =>
          Right(HabitCompletionResponse.fromHabitCompletion(completion))
        case Left(ConflictError(msg)) =>
          Left(SkippedCompletion(
            habitId     = item.habitId,
            completedOn = item.completedOn,
            reason      = s"duplicate: $msg"
          ))
      }
  }
```

The `"duplicate: "` prefix satisfies PBI-017 AC-5 (`reason` field contains
the word "duplicate"). The `ConflictError(msg)` produced by
`DoobieHabitCompletionRepository.create` already reads
`"Habit '...' already has a completion for ..."` — `cats.implicits._` `traverse`
gives sequential execution per ADR-010 §9.

## DoobieHabitCompletionRepository — existing UNIQUE handling

The repository's existing `create` method already returns
`IO[Either[ConflictError, Unit]]` and catches `class23.UNIQUE_VIOLATION`.
The batch service maps `Left(ConflictError(msg))` into a `SkippedCompletion`
with `reason = "duplicate: $msg"`. **No change is required** to
`DoobieHabitCompletionRepository.scala`. The `create` method's transaction
boundary is per-call (each `insertQuery(...).run.transact(transactor)`),
which gives PBI-016 AC-6 (per-item transaction) for free.

## OpenAPI changes

`backend/src/main/resources/openapi/openapi.yaml` gains:

### Paths

```yaml
  /users/{userId}/habits/tips:
    parameters:
      - name: userId
        in: path
        required: true
        schema:
          type: integer
          format: int64
    get:
      summary: Get LLM-generated personalised tips with retrieved corpus context
      operationId: getHabitTips
      responses:
        '200':
          description: Successful response with retrieved tips and narrative
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/TipsResponse'
        '500':
          description: Upstream LLM, embeddings, or internal error
  /users/{userId}/habits/completions/batch:
    parameters:
      - name: userId
        in: path
        required: true
        schema:
          type: integer
          format: int64
    post:
      summary: Record multiple habit completions in one request (partial success)
      operationId: recordHabitCompletionsBatch
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: array
              items:
                $ref: '#/components/schemas/BatchCompletionItem'
      responses:
        '200':
          description: Inserted and skipped lists; HTTP 200 even when all skipped
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BatchCompletionResponse'
        '400':
          description: Malformed request body
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
```

### Schemas

Append to `components.schemas`:

```yaml
    HabitTip:
      type: object
      required: [id, content]
      properties:
        id:
          type: integer
          format: int64
        content:
          type: string
    RetrievedTip:
      type: object
      required: [tip, similarityScore]
      properties:
        tip:
          $ref: '#/components/schemas/HabitTip'
        similarityScore:
          type: number
          format: double
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
    BatchCompletionItem:
      type: object
      required: [habitId, completedOn]
      properties:
        habitId:
          type: string
          format: uuid
        completedOn:
          type: string
          format: date
        note:
          type: string
          nullable: true
        completedAt:
          type: string
          format: date-time
          nullable: true
    SkippedCompletion:
      type: object
      required: [habitId, completedOn, reason]
      properties:
        habitId:
          type: string
          format: uuid
        completedOn:
          type: string
          format: date
        reason:
          type: string
    BatchCompletionResponse:
      type: object
      required: [inserted, skipped]
      properties:
        inserted:
          type: array
          items:
            $ref: '#/components/schemas/HabitCompletionResponse'
        skipped:
          type: array
          items:
            $ref: '#/components/schemas/SkippedCompletion'
```

### Modify `HabitContext` schema

Add to its `required` array: `retrievedTips`. Add to its `properties`:

```yaml
        retrievedTips:
          type: array
          items:
            type: string
```

## Test plan

### TipRepositorySpec (Testcontainers, `@Ignore`, `// requires Docker - run manually`)
File: `backend/src/test/scala/com/habittracker/repository/TipRepositorySpec.scala`.

- Spec brings up `pgvector/pgvector:pg17` via Testcontainers (not the
  default `postgres:17-alpine` — pgvector image is required for the `vector`
  extension). Run `CREATE EXTENSION IF NOT EXISTS vector` and
  `CREATE TABLE habit_tips (...)` in `beforeAll`. Liquibase changelog is
  *not* run for this spec because the corpus table is not in the
  changelog.
- Cases (PBI-015 AC-19):
  1. `insert`: stores a tip and returns a `HabitTip` with a non-zero
     generated `id`.
  2. `findSimilar (topK)`: seed 10 tips with random 1536-element
     `Vector[Float]`s, query with one of the seeded vectors; assert
     exactly `topK = 3` results.
  3. `findSimilar (ordering)`: results are ordered by `similarityScore`
     descending (`results.head.similarityScore >= results.last.similarityScore`).
  4. `findSimilar (empty table)`: empty `habit_tips` returns `Nil`
     without error.

### PromptBuilderSpec — append four cases (PBI-015 AC-20)
- `retrievedContextSection` with non-empty tips returns a non-empty
  `String`.
- `retrievedContextSection(Nil)` returns `""` without error.
- `build(ctx, retrieved)` with non-empty tips: output contains the
  retrieved-context preamble (e.g. `"Relevant habit-science tips"`).
- **Regression guard**: `build(ctx)` (no second argument) equals
  `build(ctx, Nil)` for the `fullyPopulated` fixture and equals the prior
  Phase 2 output for that fixture (existing assertions remain green; the
  new case captures the equality explicitly).

### HabitCompletionServiceSpec — append one case (PBI-016 AC-12)
Use the existing `InMemoryHabitCompletionRepository` and
`InMemoryHabitRepository` fixtures (already in the file). Seed two habits.
Pre-insert one completion. Build a four-item list:
- two valid new completions on existing habits;
- one duplicate of the pre-inserted completion;
- one referencing a non-existent `habitId`.

Assert:
- `inserted.length == 2`;
- `skipped.length == 2`;
- exactly one `skipped` entry has `reason.contains("duplicate")`;
- exactly one `skipped` entry has `reason.contains("not found")`.

### Integration — `HabitCompletionApiIntegrationSpec` — append two cases (PBI-016 AC-13, PBI-017 AC-4)
- **Duplicate single endpoint:** seed a habit, POST a completion, POST a
  duplicate. Assert HTTP 409 and `ErrorResponse.message` non-empty.
- **Batch partial success:** seed two habits, pre-insert one completion.
  POST a four-item batch matching the unit test scenario above. Assert
  HTTP 200, `inserted.length == 2`, `skipped.length == 2`, every
  `skipped.reason` is a non-empty string, and one `reason` contains
  `"duplicate"`.

`@Ignore` retained.

### SeedTipsIdempotencySpec (Testcontainers, `@Ignore`)
File: `backend/src/test/scala/com/habittracker/scripts/SeedTipsIdempotencySpec.scala`.

- Bring up `pgvector/pgvector:pg17`; create extension and table.
- Stub `EmbeddingClient.embed` is *not* attempted — the spec calls
  `SeedTips.run` directly, which means it needs a real `OPENAI_API_KEY`
  and live network access. Mark the spec with a comment
  `// requires Docker AND OPENAI_API_KEY - run manually` so the engineer
  knows. Alternative: introduce a small `FakeTipsLoader` that reads a tiny
  3-line file and uses deterministic fixed-byte-pattern vectors — but this
  diverges from the brief. Keep the spec as-is and document the manual
  setup requirement.
- Run `SeedTips.run.unsafeRunSync()` twice. Assert
  `SELECT COUNT(*) FROM habit_tips` equals the number of non-blank lines
  in `habit_tips.txt` after both runs.

### Phase 1 / Phase 2 regression (PBI-015 AC-22, AC-23)
`./gradlew test` runs to completion with zero failures. The git diff for
each frozen file is empty (verify per the HARD LIMITS list above).

## ADRs required

ADR-010 — `docs/adr/ADR-010-phase3-basic-rag.md` (written as part of this
plan; covers all three PBIs).

## Open questions

1. **pgvector image vs. default Postgres image in TipRepositorySpec.**
   Existing `DoobieAnalyticsRepositorySpec` uses
   `postgres:17-alpine`. `TipRepositorySpec` must use
   `pgvector/pgvector:pg17` to get the `vector` extension. This is a
   per-spec image choice, not a global change; it does not affect other
   Testcontainers specs. Confirm this is acceptable before the Developer
   starts.
2. **Hand-rolled pgvector wire format vs. `com.pgvector:pgvector-java`.**
   ADR-010 §5 chose hand-rolling; this avoids a new dependency. If the
   engineer prefers the library route, that requires a separate ADR and a
   small build.gradle change; the rest of `TipRepository` becomes simpler.
3. **`buildTipsQuery` template wording.** The ADR pins the structure
   ("top-3 habit names + worst two days"). The exact prose is a
   prompt-engineering question; the Developer is expected to write a
   reasonable first cut and the engineer reviews it. No prompt-changelog
   entry is required because this is the initial version.
4. **`SeedTipsIdempotencySpec` and live OpenAI calls.** The spec depends
   on a live `OPENAI_API_KEY`. If CI ever runs `@Ignore`d specs (it does
   not today), this would surface as a flaky test. Confirm that the
   "manual run only" caveat is acceptable.
5. **`BatchCompletionItem` vs. extending `CreateHabitCompletionRequest`.**
   The single endpoint reads `habitId` from the path; the batch endpoint
   needs `habitId` in each item's body. The plan introduces a separate
   `BatchCompletionItem` DTO rather than adding an optional `habitId`
   field to `CreateHabitCompletionRequest` (which would mean two valid
   shapes for the single endpoint). Confirm this DTO split is acceptable.
6. **`processOne` exhaustivity warning.** The Scala compiler will complain
   that `Either[ConflictError, Unit]` matched against
   `Right(()) | Left(ConflictError(msg))` is non-exhaustive on the type
   level (it is exhaustive on the value level). The Developer should
   enable `-Wconf:src=.*recordCompletionBatch.*:silent` *only if needed*,
   or rephrase the match to use a `.fold(...)` to avoid the warning.

This technical plan is ready for your review. Please approve or request
changes before I hand off to the Developer agent.
