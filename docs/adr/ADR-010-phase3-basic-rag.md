# ADR-010: Phase 3 basic RAG — pgvector init-script boundary, EmbeddingClient mirror of AnthropicClient, TipRepository IO contract, SeedTips Gradle integration, batch endpoint partial-success contract

## Status
Accepted

## Context

Phase 3 covers three PBIs that together complete the first end-to-end RAG
pipeline plus the operational batch endpoint a backfill workflow requires:

- **PBI-015 — Basic RAG: Personalised Tips Endpoint.** A static corpus of
  habit-science tips is embedded once (via the OpenAI embeddings API) into a
  pgvector-backed table and queried at request time by cosine similarity. The
  retrieved tips are injected into a `PromptBuilder` section that grounds the
  Anthropic narrative call. The deliverable is `GET /users/{userId}/habits/tips`
  returning `TipsResponse(tips, narrative)`.
- **PBI-016 — Batch Habit Completions Endpoint.** A new
  `POST /users/{userId}/habits/completions/batch` accepts a JSON array of
  `CreateHabitCompletionRequest` items and processes each independently,
  returning a `BatchCompletionResponse(inserted, skipped)` with HTTP 200 even
  when every item is rejected.
- **PBI-017 — One Completion Per Habit Per Day.** Verification-only PBI:
  the UNIQUE constraint already exists in migration 002 and the
  `UNIQUE_VIOLATION → ConflictError → 409` chain is already wired in
  `DoobieHabitCompletionRepository` and `ErrorHandler`. No code change is
  required beyond integration-test confirmation; the constraint is the
  mechanism that drives both the single-endpoint 409 and the batch endpoint
  skipped list.

The Phase 3 brief and PBI-015 Technical Notes raise eight architectural
questions that need an explicit, written resolution before the Developer
agent starts:

1. **pgvector setup boundary.** `CREATE EXTENSION vector` and the
   `habit_tips` table must exist before the application starts. Two options:
   (a) a Liquibase changeset, (b) a Docker Compose init script. Migration 005
   already added `completed_at`; the next free Liquibase slot is 006.
2. **EmbeddingClient design.** Same direct-sttp shape as `AnthropicClient`
   per HARD LIMITS, but with a different vendor (OpenAI), a different response
   shape (a JSON `data[0].embedding` array), and a different startup-key
   forcing handle (`OPENAI_API_KEY` instead of `ANTHROPIC_API_KEY`).
3. **TipRepository effect type.** The Phase 3 brief shows `F[_]` in
   repository signatures; Architect Memory and the rest of the codebase
   use `IO` directly. Architect Memory marks the brief as wrong.
4. **TipRepository similarity SQL.** The brief mandates a *named val* for the
   query and three inline comments explaining cosine similarity, the
   `<=>` operator, and the ORDER BY semantics.
5. **pgvector wire format in Doobie.** No `Meta[Vector[Float]]` ships with
   Doobie or `doobie.postgres`. The choices are: write a custom Meta that
   serialises to pgvector's textual format `[v1,v2,...]`, or pull in the
   `com.pgvector:pgvector-java` adapter as a new dependency.
6. **SeedTips Gradle integration.** `IOApp.Simple` needs a Gradle entry
   point so the engineer can run `./gradlew runSeedTips` (PBI-015 AC-3).
   Reusing the `application` plugin's `mainClass` would require swapping it
   away from `Main` — unacceptable. A separate `JavaExec` task is needed.
7. **HabitContext extension.** `retrievedTips: List[String] = Nil` must
   default to `Nil` so the Phase 1/2 frozen tests
   (`InsightPromptSpec`, `PromptBuilderSpec`) keep compiling without edits.
8. **Batch endpoint design.** Route placement, response shape, partial-success
   contract, per-item transaction semantics, and how the existing
   `ConflictError` mapping is reused without duplication.

The HARD LIMITS block forbids any modification to `AnthropicClient.scala`,
`InsightPrompt.scala`, `InsightsRoutes.scala`, `AnalysisRoutes.scala`, the
existing single-completion route, or any Phase 1 / Phase 2 test file. ADR-008
and ADR-009 lock conventions this ADR builds on (file layout, AnthropicClient
shape, no abstraction over the LLM call, Testcontainers + `@Ignore`
discipline, semiauto codecs, Gradle build, ScalaTest pattern, route
registration order in `AppResources`).

A practical context point that shaped the embedding-vendor choice: the
engineer already holds an `OPENAI_API_KEY` and selected
`text-embedding-3-small` (1536 dimensions) before the architecture work
began. This ADR does not relitigate that choice; it documents the integration
constraints it implies.

---

## Decision

### 1. pgvector setup lives in a Docker Compose init script — not Liquibase

`CREATE EXTENSION IF NOT EXISTS vector` and the `habit_tips` table are
created by `infra/db/init/01_enable_vector.sql`, mounted into the Postgres
container via the existing `./infra/db/init:/docker-entrypoint-initdb.d:ro`
bind in `docker-compose.yaml`. A second file
`infra/db/init/02_create_habit_tips.sql` is added for the table itself, kept
separate from the extension setup for readability.

**Why init script, not Liquibase:**

- `CREATE EXTENSION vector` is a privileged DDL that requires the
  `superuser`-equivalent role provided by the Postgres init container. Local
  Liquibase runs use the `habituser` application role, which does not have
  permission to install extensions in the production-shaped image.
- The extension is *infrastructure* — it ships with the
  `pgvector/pgvector:pg17` image and is expected to be present before the
  application connects. Liquibase changesets manage the *application schema*;
  the boundary already established by ADR-003.
- The init script directory (`infra/db/init/`) already exists and already
  carries `01_enable_vector.sql` (created earlier as preparation). Adding the
  table next to it preserves a single, ordered "infrastructure DDL" surface.
- Liquibase migration 002 created `habit_completions` and the
  `uq_habit_completions_habit_day` UNIQUE constraint. Migration 005 added
  `completed_at`. Both are application-owned tables. `habit_tips` is a
  *corpus* table whose lifecycle is tied to the embedding model choice and
  the seeding script, not to the application's evolving feature schema —
  another reason to keep it out of the changelog.
- A Docker Compose `down -v` followed by `up -d` is the agreed reset path
  for local development. The init scripts re-run on a fresh volume; the
  habit corpus is then re-seeded with `./gradlew runSeedTips`.

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

-- Optional uniqueness on content prevents the SeedTips idempotency check
-- from racing if two seed runs overlap. SeedTips also performs a
-- pre-insert SELECT, so this is a belt-and-braces guard.
CREATE UNIQUE INDEX IF NOT EXISTS uq_habit_tips_content
    ON habit_tips (md5(content));
```

The `md5(content)` index is preferred over `UNIQUE (content)` because pgvector
tip strings are short paragraphs that may exceed the B-tree size limit on
some PostgreSQL configurations; an `md5` hash collapses every content string
to a fixed 32-byte key. SeedTips relies on its own SELECT-then-INSERT
idempotency, so the index is a defensive layer rather than the primary
mechanism.

**No Liquibase 006 in this phase.** Liquibase 006 is reserved for genuine
schema changes in a future PBI.

### 2. `EmbeddingClient` mirrors `AnthropicClient` exactly — no trait, no constructor, key forced at AppResources

`com.habittracker.client.EmbeddingClient` is a Scala `object` with the same
five structural elements as `AnthropicClient`:

1. Top-level constants for `MODEL`, `API_URL`, `DIMENSION`.
2. A `private val API_KEY: String` resolved from `sys.env.get("OPENAI_API_KEY")`
   at object init, with `sys.error(...)` on missing or blank value.
3. A `val API_KEY_CHECK: Unit` named forcing-handle so `AppResources.make`
   can fail at startup, before any HTTP request.
4. A polymorphic `embed[F[_]: Async](text: String): F[Vector[Float]]` method
   that constructs an sttp request with `HttpClientCatsBackend.resource[F]()`,
   parses the JSON response (`data[0].embedding`), and raises typed errors on
   non-200 responses or unparseable bodies.
5. No trait, no abstract class, no DI binding. The method is called directly
   from the route handler.

Wiring in `AppResources.make`:

```scala
_ <- Resource.eval(IO(AnthropicClient.API_KEY_CHECK))
_ <- Resource.eval(IO(EmbeddingClient.API_KEY_CHECK))
```

The two startup checks are sequential. If either key is missing, the app
exits before the server binds to a port — the same contract Phase 1
established for `ANTHROPIC_API_KEY`.

**Why this exact shape (and not, e.g., a shared trait `LlmClient`):**

- Architect Memory locks the no-abstraction rule: "EmbeddingClient must
  follow the exact same pattern as AnthropicClient: no trait, no constructor,
  direct sttp call visible at call site". A shared trait would defeat the
  point of the pattern, which is that any reviewer can audit the HTTP call
  end-to-end without indirection.
- The two clients call different vendors with different request/response
  shapes. The Anthropic call posts a `messages` array and parses
  `content[0].text`. The OpenAI embeddings call posts an `input` field and
  parses `data[0].embedding`. A common abstraction would only help if the
  vendors' wire formats converged, which they do not.
- `AnthropicClient` is frozen (HARD LIMIT). Even if a future ADR wanted a
  trait, retrofitting it into `AnthropicClient` is not allowed in Phase 3.
- The single test surface for `EmbeddingClient` is the manual live-API check
  the engineer runs after seeding. This matches the `AnthropicClient`
  trade-off ADR-008 §2 already accepts.

**Inline comment requirement (PBI-015 AC-13):** above the `MODEL` constant,
the comment must explain in plain terms that an embedding is a list of
1536 numbers representing the semantic meaning of a text and that texts with
similar meaning produce vectors close together in 1536-dimensional space. The
comment is a genuine explanation, not a code restatement. The Developer must
write the prose; this ADR pins the location and the conceptual scope.

The full source for `EmbeddingClient` is in PLAN-015 §"New components".

### 3. `TipRepository` uses `IO` directly — `class TipRepository(xa: Transactor[IO])`, not `F[_]: Async`

The phase brief shows `F[_]` in the `TipRepository` signatures. That is
documentation drift inherited from a non-canonical template; Architect Memory
flags this explicitly ("Effect Type Convention: TipRepository must use IO
directly, not F[_]: Async — phase brief has this wrong"). Every other
repository in the codebase (`DoobieHabitRepository`,
`DoobieHabitCompletionRepository`, `DoobieAnalyticsRepository`) is
`(xa: Transactor[IO])` with `IO[_]` returns. `TipRepository` follows that
convention.

```scala
final class TipRepository(transactor: Transactor[IO]) {

  def insert(content: String, embedding: Vector[Float]): IO[HabitTip]

  def findSimilar(
      queryEmbedding: Vector[Float],
      topK:           Int
  ): IO[List[RetrievedTip]]
}
```

**No trait `TipRepository`.** The codebase introduces a trait when there is
either an in-memory test double (`InMemoryHabitRepository`) or polymorphic
construction. Phase 3 has neither: `TipRepository` is exercised only by
Testcontainers (per AC-19, all four cases require `@Ignore`-flagged Docker
specs) and is constructed only in `AppResources` and `SeedTips`. A bare
class avoids creating a trait that no caller would substitute.

### 4. `TipRepository.similaritySearchSql` is a named `Fragment` val with three required inline comments

The cosine-similarity query is bound to a named `val` per AC-12:

```scala
// Cosine similarity measures the angle between two embedding vectors.
// A score of 1.0 means the vectors point in exactly the same direction
// (identical meaning); 0.0 means they are orthogonal (unrelated meaning).
// pgvector's `<=>` operator returns *cosine distance*, defined as
// 1 - cosineSimilarity. We subtract from 1 to translate distance back into
// the more intuitive similarity score before returning it to the caller.
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
```

The Phase 3 brief showed `similaritySearchSql` as a single
parameterless `Fragment`. This ADR refines it to a `def` returning
`Query0[(Long, String, Double)]` because Doobie cannot bind a `Vector[Float]`
to the `?::vector` placeholder without a `Meta[Vector[Float]]` instance, and
pgvector-java is not on the classpath (see §6 below). The query name
"similaritySearchSql" is preserved per AC-12; it is a `def` not a `val`
because it is parameterised — the name and the explanatory comment are
the load-bearing requirements, not the val/def keyword.

`embeddingToPgLiteral(v: Vector[Float]): String` is a private helper inside
`TipRepository` that converts the vector into pgvector's textual literal
format `[v1,v2,...]` and is interpolated as a `String` Fragment, then cast
back to `vector` by the `::vector` cast. This is the supported way to send
a pgvector value over the wire when no Doobie `Meta` exists for the type.

**Inline comment on `findSimilar` (AC-15):**

```scala
// pgvector's `<=>` operator returns cosine *distance*, where smaller is
// closer. Ordering ascending by `<=>` therefore returns rows from most
// similar (smallest distance) to least similar (largest distance). The
// caller sees results sorted by semantic similarity descending.
def findSimilar(
    queryEmbedding: Vector[Float],
    topK:           Int
): IO[List[RetrievedTip]] = ...
```

### 5. pgvector wire format — custom textual literal, no new dependency

Doobie has no built-in `Meta[Vector[Float]]`. Two ways to bridge:

**Option A — pull in `com.pgvector:pgvector-java:0.1.6`**, which provides a
`PGvector` JDBC type, then write a Doobie `Meta[PGvector]`. New dependency,
new wire layer.

**Option B — serialise the `Vector[Float]` to pgvector's textual literal
form `[v1,v2,...]` and `::vector`-cast it in the SQL.** No new dependency.
The pgvector documentation guarantees the textual form is round-trip-safe.

This ADR chooses **Option B**. Justification:

- The Phase 3 HARD LIMITS forbid RAG framework dependencies. While
  `pgvector-java` is not LangChain4j, the spirit of the constraint is "every
  step must be explicit Scala code with inline comments explaining what is
  happening". Hand-rolling the textual literal makes the wire format
  explicit and learnable.
- pgvector-java's `PGvector` would shift complexity (a Doobie `Meta` plus
  a JDBC type registration) without removing it. The literal-format helper
  is six lines of code with no library to track.
- The literal-format helper is exercised by every `findSimilar` call and by
  `insert`, so it is fully covered by the Testcontainers spec for the
  repository.
- A future PBI that needs more complex pgvector operations (HNSW index,
  sparse vectors, half-precision) can revisit the choice with its own ADR.

The helper:

```scala
private def embeddingToPgLiteral(v: Vector[Float]): String =
  v.mkString("[", ",", "]")
```

For reading: the SELECT projects three primitive columns
(`id BIGINT`, `content TEXT`, `score DOUBLE PRECISION`); none of them is
the `embedding vector(1536)` column itself. The repository never reads
embeddings back as `Vector[Float]`, so no read-side `Meta` is required.

### 6. `SeedTips` is `IOApp.Simple` invoked via a new Gradle `JavaExec` task

`backend/src/main/scala/com/habittracker/scripts/SeedTips.scala`:

```scala
package com.habittracker.scripts

import cats.effect.{IO, IOApp}
// ...

object SeedTips extends IOApp.Simple {
  override def run: IO[Unit] = ...
}
```

Gradle wiring (`backend/build.gradle`):

```gradle
tasks.register('runSeedTips', JavaExec) {
    group       = 'application'
    description = 'Embeds and inserts the habit-tips corpus into the habit_tips table.'
    classpath   = sourceSets.main.runtimeClasspath
    mainClass   = 'com.habittracker.scripts.SeedTips'
    dependsOn   = ['classes']
    standardInput = System.in
    // Pass through the relevant env vars (OPENAI_API_KEY, DB_URL, etc.).
    // Gradle inherits the parent process environment by default; no extra
    // configuration is required for env-based secrets.
}
```

**Why a separate `JavaExec` task and not `gradle run -PmainClass=...`:**

- The `application` plugin's `run` task uses `mainClass = 'com.habittracker.Main'`
  configured at the top of `build.gradle`. Overriding `mainClass` per
  invocation works in newer Gradle but is confusing — `run` and `runSeedTips`
  doing different things via the same task is opaque.
- A dedicated `JavaExec` task is self-documenting: the engineer types
  `./gradlew runSeedTips` and sees a task with a clear name and `description`
  in `./gradlew tasks --group=application`.
- The task `dependsOn = ['classes']` so a fresh source change is recompiled
  before the script runs; this matches engineer expectations.
- Idempotency is delivered by `SeedTips.run` itself (per-line
  `SELECT 1 FROM habit_tips WHERE content = ?` before each insert, plus the
  defensive `uq_habit_tips_content` index from §1). The Gradle task does
  nothing special for re-runs.

### 7. `HabitContext.retrievedTips: List[String] = Nil` — default keeps frozen tests compiling

`HabitContext` already follows the "append new fields with default values"
pattern from ADR-009. Phase 3 adds:

```scala
final case class HabitContext(
    userId:             Long,
    streaks:            Map[UUID, Int],
    completionByDay:    Map[String, Double],
    consistencyRanking: List[(String, Double)],
    timeOfDayPatterns:  Map[String, Double]              = Map.empty,
    correlatedPairs:    List[(String, String, Double)]   = Nil,
    momentumScores:     Map[UUID, Double]                = Map.empty,
    retrievedTips:      List[String]                     = Nil   // Phase 3
)
```

**Why default `Nil`:** the frozen `InsightPromptSpec` constructs
`HabitContext(userId, streaks, completionByDay, consistencyRanking)` —
four positional arguments, no `retrievedTips`. The frozen `PromptBuilderSpec`
constructs the seven-field shape, also without `retrievedTips`. The
default makes both compilations work without modifying test sources
(HARD LIMIT).

`DefaultAnalyticsService.buildHabitContext` always supplies all eight fields
explicitly by name; the default is never triggered in production. The
OpenAPI spec marks `retrievedTips` as `required` because the JSON wire
format always includes it.

**Why `List[String]` and not `List[RetrievedTip]`:** the field is the
*query input* — the list of strings the LLM is told it should reason about,
not the retrieved-tip *output objects* with similarity scores. The output
goes in `TipsResponse.tips` directly. Decoupling avoids a circular
dependency and matches the Phase 3 brief's wording.

### 8. Tips endpoint composition — `TipsRoutes(analyticsService, tipRepo)` chains existing components in a single `for`

The tips endpoint composes six steps in one `for`-comprehension inside the
new `TipsRoutes` class:

1. `service.buildHabitContext(userId)` — same `AnalyticsService` instance
   already used by `InsightsRoutes` and `AnalysisRoutes`. The Phase 3 RAG
   path does not extend `buildHabitContext`; it consumes the existing
   eight-field context as-is. `retrievedTips` on the returned `HabitContext`
   is `Nil` because the service does not know about RAG.
2. `service.buildTipsQuery(ctx)` — a new pure method on `AnalyticsService`
   (added to the trait and `DefaultAnalyticsService`) that derives a single
   plain-text query string from `HabitContext`. Pure: no `IO`, no `F[_]`.
3. `EmbeddingClient.embed[IO](query)` — direct sttp call, key already
   forced at startup.
4. `tipRepo.findSimilar(queryEmbedding, TOP_K)` — pgvector cosine distance
   ascending, `LIMIT TOP_K`. `TOP_K` is a private named `val` on
   `TipsRoutes` set to `3`.
5. `PromptBuilder.build(ctx, retrieved)` — extended `build` signature with
   default `Nil`. The existing `AnalysisRoutes` call site
   (`PromptBuilder.build(ctx)`) compiles unchanged.
6. `AnthropicClient.complete[IO](PromptBuilder.HABIT_COACH_SYSTEM_PROMPT, prompt)`
   — same model and system prompt as the analysis endpoint.

`TipsResponse(tips = retrieved, narrative = narrative)` is encoded via a new
`tipsResponseEncoder` in `AnalyticsCodecs`.

**Route placement.** `TipsRoutes` is appended *after* `AnalysisRoutes` and
*before* `HabitRoutes` in `AppResources.make`. Architect Memory locks this
order. The new `BatchCompletionRoutes` (§9) sits between `TipsRoutes` and
`HabitRoutes`.

```scala
allRoutes = new DocsRoutes().routes <+>
            new InsightsRoutes(analyticsService).routes <+>
            new AnalysisRoutes(analyticsService).routes <+>
            new TipsRoutes(analyticsService, tipRepo).routes <+>
            new BatchCompletionRoutes(completionSvc).routes <+>
            new HabitRoutes(habitService).routes <+>
            new HabitCompletionRoutes(completionSvc).routes
```

**`PromptBuilder.build` extension.** The new signature is

```scala
def build(ctx: HabitContext, tips: List[RetrievedTip] = Nil): String =
  List(
    streakSection(ctx),
    dayPatternSection(ctx),
    rankingSection(ctx),
    timeOfDaySection(ctx),
    correlationSection(ctx),
    momentumSection(ctx),
    retrievedContextSection(tips)
  ).filter(_.nonEmpty).mkString("\n\n")
```

The default `Nil` argument keeps `AnalysisRoutes`'s call site
(`PromptBuilder.build(ctx)`) working without modification. When `tips` is
empty, `retrievedContextSection(Nil)` returns `""` and is filtered out.

**Inline comment on `retrievedContextSection` (AC-16):** explains that
"grounding" in RAG means injecting retrieved external knowledge into the
prompt so the LLM synthesises its response from those facts rather than from
its training data alone. The comment is genuine prose, not a restatement of
the code.

### 9. Batch endpoint design — new `BatchCompletionRoutes`, partial-success contract, per-item transaction

PBI-016 introduces three artefacts:

1. **Two new DTOs** in `com.habittracker.http.dto`:
   ```scala
   final case class BatchCompletionResponse(
       inserted: List[HabitCompletionResponse],
       skipped:  List[SkippedCompletion]
   )
   final case class SkippedCompletion(
       habitId:     UUID,
       completedOn: LocalDate,
       reason:      String
   )
   ```
2. **One new service method** on `HabitCompletionService` and
   `DefaultHabitCompletionService`:
   ```scala
   def recordCompletionBatch(
       userId: Long,
       items:  List[CreateHabitCompletionRequest]
   ): IO[BatchCompletionResponse]
   ```
   The batch service iterates items sequentially via `traverse`, calling
   `habitRepo.findActiveById(userId, habitId)` then
   `completionRepo.create(completion)` per item — exactly the same logic as
   `recordCompletion`, with the `Either[AppError, ...]` mapped to
   `Inserted` vs `SkippedCompletion(reason = ...)`.
3. **One new route class** `BatchCompletionRoutes`.

**Why a new class and not an extension to `HabitCompletionRoutes`:**

- `HabitCompletionRoutes` is a frozen contract (PBI-016 AC-10): "Existing
  POST /users/{userId}/habits/{habitId}/completions is unmodified". Adding
  the batch case to the same `HttpRoutes.of[IO] { ... }` block introduces
  edit risk to the existing pattern matchers. The `UUIDVar(habitId)`
  pattern in the existing routes is order-sensitive — a new case branch
  inserted in the wrong position would either shadow the single-completion
  route or itself be shadowed.
- A new file mirrors the Phase 1/2 precedent (`InsightsRoutes` /
  `AnalysisRoutes` are sibling classes per ADR-008/ADR-009).
- `BatchCompletionRoutes` constructor takes the same
  `HabitCompletionService` instance as `HabitCompletionRoutes` — no new
  service instance, no DI churn in `AppResources`.

**Route precedence.** The batch URL is
`/users/{userId}/habits/completions/batch` — three path segments after
`habits` (`completions / batch` plus the literal `users / {userId} / habits`).
The single-completion URL is
`/users/{userId}/habits/{habitId}/completions` — two path segments after
`habits` plus a UUID. Because the batch URL has the literal `completions`
where the single URL expects a UUID, http4s pattern matching disambiguates
without ordering. However, to make the precedence visible in source, the
batch route is in a *separate routes object* registered *before*
`HabitCompletionRoutes` in the `<+>` chain.

**Per-item transaction isolation.** `DoobieHabitCompletionRepository.create`
already wraps each insert in its own `.transact(transactor)` call.
`recordCompletionBatch` therefore does the right thing automatically by
calling `completionRepo.create(completion)` per item — no whole-batch
`transact`. AC-6 is satisfied by composition; no new repository code is
needed.

**Skipped reason mapping.** Two cases:
- `habitRepo.findActiveById(userId, habitId).map(_.isEmpty) == true`
  → `SkippedCompletion(habitId, completedOn, reason = "habit not found or
  not active for this user")`.
- `completionRepo.create(c).map(_.left)` → `Left(ConflictError(msg))`
  →  `SkippedCompletion(habitId, completedOn, reason = "duplicate: " + msg)`.
  The `"duplicate"` substring is required by PBI-017 AC-5; the reason
  composes the existing `ConflictError` message that
  `DoobieHabitCompletionRepository.create` emits.

**Always HTTP 200.** Per AC-9, even an all-skipped batch returns 200 with
`inserted: []` and `skipped: [...]`. There is no `IO[Either[AppError, ...]]`
on the new service method — partial success is the contract. The route
returns `Ok(response)` unconditionally.

**Sequential vs parallel item processing.** PBI-016 does not require
parallelism. The batch service uses sequential `traverse` (cats
`List.traverse`), not `parTraverse`. Reasons:
- Doobie's `HikariCP` pool is sized for the eight context-assembly queries
  the analysis endpoint already runs in parallel (ADR-009 §5). A 50-item
  batch all calling `findActiveById` + `create` concurrently would saturate
  the pool.
- The item ordering in the response is preserved by sequential traversal
  (`inserted` and `skipped` lists reflect the request order). PBI-016 does
  not require this, but it makes test assertions deterministic.
- A future PBI can switch to `parTraverse` after benchmarking; ADR-010 does
  not lock the choice.

**No batch-size limit.** PBI-016 AC out-of-scope explicitly says no cap is
enforced. A future PBI may add one.

**Codecs.** `BatchCompletionResponse` and `SkippedCompletion` codecs are
added to `CompletionCodecs` via `deriveEncoder`/`deriveDecoder` (semiauto).
`HabitCompletionResponse` codec is reused unchanged. UUID and LocalDate
codecs are reused from the existing `CommonCodecs` / `HabitCodecs`.

### 10. PBI-017 is verification-only — no code change, ADR documents the chain

The UNIQUE constraint `uq_habit_completions_habit_day` was created in
migration 002. `DoobieHabitCompletionRepository.create` already catches
`sqlstate.class23.UNIQUE_VIOLATION` and returns `Left(ConflictError(...))`.
`ErrorHandler.toResponse` already maps `ConflictError` to `Conflict(...)`
(HTTP 409). The single-endpoint 409 path is end-to-end wired.

Phase 3 confirms the chain via:

- Two integration test cases (Testcontainers, `@Ignore`):
  - **Single endpoint:** insert a completion, then attempt a duplicate, assert
    HTTP 409 and `ErrorResponse.message` non-empty.
  - **Batch endpoint:** include a duplicate among other items, assert it
    appears in the `skipped` list with a `reason` containing `"duplicate"`,
    HTTP 200.
- This ADR section, which records that the constraint exists and is the
  mechanism that drives both behaviours.

**No migration 006.** The next free Liquibase slot remains 006 for genuine
schema changes in a future PBI.

### 11. OpenAPI updates — additive, three new schemas, two new paths

`backend/src/main/resources/openapi/openapi.yaml` gains:

- `HabitContext.retrievedTips` property (array of strings, required).
- `HabitTip` schema (id: int64, content: string).
- `RetrievedTip` schema (tip: HabitTip, similarityScore: number).
- `TipsResponse` schema (tips: array of RetrievedTip, narrative: string).
- `BatchCompletionResponse` schema (inserted: array of
  `HabitCompletionResponse`, skipped: array of `SkippedCompletion`).
- `SkippedCompletion` schema (habitId: uuid, completedOn: date,
  reason: string).
- `GET /users/{userId}/habits/tips` path with `getHabitTips` operationId.
- `POST /users/{userId}/habits/completions/batch` path with
  `recordHabitCompletionsBatch` operationId.

The exact YAML diff is in PLAN-015 §"OpenAPI changes".

### 12. Build tool is Gradle, dependencies do not grow

The Phase 3 brief mentions "sbt" in places (documentation drift, ADR-008 §7
and ADR-009 §10 already corrected this). All commands in PLAN-015 use
`./gradlew compileScala`, `./gradlew test`, and the new
`./gradlew runSeedTips`. The `application` plugin's `run` task is unchanged.

**No new dependencies are added.** All required modules
(sttp, circe, doobie, http4s, cats-effect, ScalaTest, Testcontainers,
liquibase) are already on the classpath from prior phases. The pgvector
wire format is hand-rolled (§5), avoiding `com.pgvector:pgvector-java`. If
the engineer prefers the library route, that is a separate ADR.

---

## Consequences

**Easier:**

- Adding a Phase 4 second retrieval source (user notes) is a parallel
  `NoteRepository` mirroring `TipRepository`, a parallel
  `personalNotesSection` on `PromptBuilder`, and a `parTupled` over
  `(EmbeddingClient.embed, EmbeddingClient.embed)` in the route. All three
  Phase 3 components are reused unchanged.
- pgvector setup lives in a single, ordered init-script directory. New
  embedding-backed tables are added by appending another `0N_*.sql` file —
  no Liquibase ceremony.
- The batch endpoint's partial-success contract composes the existing
  `ConflictError` mapping. The `"duplicate"` reason substring is a string
  concatenation, not a new type, so the chain does not invent a new error
  shape.
- `EmbeddingClient` has the same shape as `AnthropicClient`, so any reviewer
  familiar with the Anthropic call can audit the OpenAI call in the same
  number of lines.
- `SeedTips` is a normal `IOApp.Simple` reachable from the engineer's
  laptop and from CI without the test runner. A future "re-embed corpus"
  flow would use the same task with a `--force` flag.

**Harder / trade-offs:**

- The pgvector textual literal helper is a manual serialiser. It assumes
  `Float.toString` produces a value pgvector parses, which is true for
  finite floats but not for `NaN` or `Infinity`. The helper does not
  defensively reject those values; a malformed embedding (e.g. truncated
  OpenAI response) would surface as a Postgres parse error rather than a
  validated `EmbeddingClient` failure. This is acceptable for the PoC; a
  future PBI may add a sanity check.
- `TipRepository` has no in-memory test double (no trait). The four
  `TipRepositorySpec` cases all require Docker + Testcontainers. CI without
  Docker skips them via `@Ignore`. This matches the pattern already
  established for `DoobieAnalyticsRepositorySpec`.
- `EmbeddingClient` has no unit-level test, same trade-off as
  `AnthropicClient` (ADR-008 §2). The first manual seed run is the
  end-to-end check.
- The batch endpoint's sequential `traverse` means a batch of 100 items
  issues 200 sequential database round-trips (one `findActiveById` plus one
  `create` per item). For a PoC this is fine; the response is still bounded
  by network latency, not pool size.
- `OPENAI_API_KEY` is a *second* startup-failure surface. Either key
  missing aborts boot; the operator must set both.
- The `md5(content)` index on `habit_tips` adds a small write-time cost to
  `SeedTips`. For a ~25-line corpus this is negligible.

**Locked in:**

- pgvector setup is *infrastructure* and lives in `infra/db/init/`. New
  embedding tables go there, not in the Liquibase changelog.
- `EmbeddingClient` is a no-trait, no-constructor `object` with `MODEL`,
  `API_URL`, `DIMENSION`, `API_KEY_CHECK` and a polymorphic `embed[F[_]: Async]`
  signature. Phase 4 reuses it unchanged.
- `TipRepository` uses `IO` directly. Phase 4's `NoteRepository` follows
  the same pattern (no trait, `IO` returns, named `similaritySearchSql`,
  same three inline comments).
- `HabitContext.retrievedTips: List[String] = Nil` is the eighth and final
  Phase 3 field. Phase 4 does not append to `HabitContext` (it uses
  `RetrievedTip` arguments threaded through `PromptBuilder.build`, not
  context fields).
- `PromptBuilder.build(ctx, tips: List[RetrievedTip] = Nil)` is the
  authoritative signature. Phase 4 extends it once more
  (`tips`, `notes` defaults to `Nil`), per the Phase 3 brief carry-over.
- `TOP_K = 3` lives on `TipsRoutes` as a `private val`. Phase 4 will
  introduce `TIPS_TOP_K` and `NOTES_TOP_K` per the carry-over note.
- The batch endpoint's partial-success contract (HTTP 200 always,
  `BatchCompletionResponse(inserted, skipped)`, `"duplicate"` substring in
  reason) is the public API. Future bulk endpoints follow this pattern
  unless a separate ADR overrides it.
- Liquibase migration 006 is reserved for genuine schema changes in a
  future PBI; the UNIQUE constraint in 002 is the source of truth for the
  one-completion-per-day rule.
- Build is Gradle. No new runtime or test dependency is added in Phase 3.
