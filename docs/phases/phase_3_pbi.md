# PBI-015: Phase 3 — Basic RAG: Personalised Tips Endpoint

## User story

As the habit tracker application, I want a `/tips` endpoint that embeds a query
derived from the user's habit context, retrieves the top-3 semantically similar tips
from a pre-seeded pgvector corpus, and calls the Anthropic API with those tips as
grounding context, so that users receive personalised, evidence-grounded habit tips
rather than generic LLM responses.

---

## Acceptance criteria

- [ ] **AC-1 — Docker Compose starts cleanly with pgvector extension enabled.**
  `docker compose -f infra/docker-compose.yml up -d` completes without error.
  A Postgres session confirms `SELECT * FROM pg_extension WHERE extname = 'vector'`
  returns one row.

- [ ] **AC-2 — habit_tips table exists with vector(1536) column.**
  After `docker compose up`, the `habit_tips` table exists with columns:
  `id BIGSERIAL PRIMARY KEY`, `content TEXT NOT NULL`, `embedding vector(1536) NOT NULL`.
  The table is created by a Docker Compose init script (e.g. `docker/init.sql`),
  not by a Liquibase migration.

- [ ] **AC-3 — SeedTips runs without error.**
  Running `./gradlew runSeedTips` from the `backend/` directory completes with
  exit code 0 and logs progress in the form
  `"Seeded tip N/total: [first 50 chars]"` for each newly inserted tip.

- [ ] **AC-4 — SeedTips is idempotent — running twice produces no duplicate rows.**
  Running `./gradlew runSeedTips` a second time against a database that already
  contains the seeded tips produces no new rows. The row count in `habit_tips`
  equals the number of lines in `habit_tips.txt` after both runs.

- [ ] **AC-5 — GET /users/{userId}/habits/tips returns HTTP 200.**
  A GET request to `/users/{userId}/habits/tips` with a valid `userId` (Long)
  returns HTTP 200. The response body is valid JSON.

- [ ] **AC-6 — Response body deserialises to TipsResponse with tips and narrative.**
  The JSON response deserialises without error to `TipsResponse` with exactly two
  fields: `tips` (type `List[RetrievedTip]`) and `narrative` (type `String`).

- [ ] **AC-7 — tips list contains exactly TOP_K (3) items.**
  The `tips` list in the response has exactly 3 elements when the `habit_tips` table
  contains at least 3 rows. `TOP_K` is a named constant (value: 3) defined in the
  route or service — not a magic number at the call site.

- [ ] **AC-8 — Each RetrievedTip has a non-zero similarityScore.**
  Every element in the `tips` list has a `similarityScore` field that is a Double
  greater than 0.0.

- [ ] **AC-9 — narrative is a non-empty string.**
  The `narrative` field in the response is a non-empty string produced by a call to
  the Anthropic API (`claude-sonnet-4-20250514` model). It is not a placeholder or
  hardcoded value.

- [ ] **AC-10 — EmbeddingClient contains a direct sttp call — same pattern as AnthropicClient.**
  `com.habittracker.client.EmbeddingClient` is a Scala `object` (no trait, no
  constructor). It makes an HTTP POST to `https://api.openai.com/v1/embeddings` using
  sttp with the cats-effect backend, following the identical structural pattern as
  `AnthropicClient`. No abstraction layer or wrapper trait exists.

- [ ] **AC-11 — OPENAI_API_KEY read from environment — not hardcoded.**
  `EmbeddingClient` reads `OPENAI_API_KEY` from the process environment. A missing
  or empty key causes a startup failure via `Resource.eval(IO(...))` in
  `AppResources.make`, equivalent to the existing `AnthropicClient.API_KEY_CHECK`.
  The key value does not appear in any source file.

- [ ] **AC-12 — TipRepository.similaritySearchSql is a named val.**
  `com.habittracker.repository.TipRepository` declares the pgvector similarity
  search query as a named `val` (or `def`) called `similaritySearchSql` (or a
  descriptive variant). The SQL is not an anonymous string literal at the call site.

- [ ] **AC-13 — Inline comment in EmbeddingClient explains what an embedding vector is.**
  At the location specified in the Scope (above the `MODEL` constant), there is an
  inline comment explaining in plain terms what an embedding vector is — specifically
  that it is a list of numbers representing semantic meaning, and that texts with
  similar meaning produce vectors close together in high-dimensional space. The
  comment is a genuine explanation, not a restatement of the code.

- [ ] **AC-14 — Inline comment on TipRepository.similaritySearchSql explains cosine similarity.**
  At the location of `similaritySearchSql`, there is an inline comment explaining
  cosine similarity in plain terms: what a score of 1.0 and 0.0 mean, that `<=>`
  is pgvector's cosine distance (not similarity), and why subtracting from 1 gives
  the similarity score. The comment is a genuine explanation, not a restatement of
  the code.

- [ ] **AC-15 — Inline comment on TipRepository.findSimilar explains ORDER BY distance.**
  At the `findSimilar` method, there is an inline comment explaining why ordering by
  cosine distance ascending yields results sorted by semantic similarity descending.
  The comment is a genuine explanation, not a restatement of the code.

- [ ] **AC-16 — Inline comment in PromptBuilder.retrievedContextSection explains grounding.**
  At the `retrievedContextSection` method, there is an inline comment explaining
  what "grounding" means in RAG: that retrieved tips are injected to anchor the
  LLM's response in external knowledge, and how this differs from a cold (ungrounded)
  prompt. The comment is a genuine explanation, not a restatement of the code.

- [ ] **AC-17 — TOP_K is a named constant.**
  The value 3 used as `topK` argument to `TipRepository.findSimilar` is bound to a
  named constant (e.g. `val TOP_K: Int = 3` or `final val TOP_K = 3`) in the route
  or service file. The literal `3` does not appear at the call site.

- [ ] **AC-18 — PromptBuilder.build accepts tips with default Nil (backward compatible).**
  `PromptBuilder.build` is extended to the signature
  `def build(ctx: HabitContext, tips: List[RetrievedTip] = Nil): String`.
  Calling `build(ctx)` with no `tips` argument compiles and produces the same output
  as the Phase 2 `build(ctx)` call (regression: existing AnalysisRoutes call site
  compiles without modification).

- [ ] **AC-19 — All TipRepository tests pass.**
  The following four test cases exist in a Testcontainers spec
  (annotated `@Ignore`, requires Docker) and pass when run manually:
  - `insert`: stores a tip and returns a `HabitTip` with a non-null generated `id`.
  - `findSimilar (topK)`: given 10 seeded tips, returns exactly `TOP_K` results.
  - `findSimilar (ordering)`: results are ordered by `similarityScore` descending
    (first result score >= last result score).
  - `findSimilar (empty table)`: an empty `habit_tips` table returns an empty list
    without error.

- [ ] **AC-20 — All new PromptBuilder tests pass.**
  The following four pure unit tests (no IO, no Docker, no `@Ignore`) exist and
  pass with `./gradlew test`:
  - `retrievedContextSection` with non-empty tips returns a non-empty `String`.
  - `retrievedContextSection` with an empty list returns an empty `String` without
    throwing.
  - `build` with non-empty tips: output contains the retrieved context section
    content.
  - `build` without tips (default `Nil`): output equals the Phase 2 `build(ctx)`
    output for the same `ctx` (regression guard).

- [ ] **AC-21 — SeedTips idempotency test passes.**
  A test (Testcontainers, annotated `@Ignore`) runs `SeedTips.run` twice against
  a clean test database and asserts that the row count in `habit_tips` equals the
  number of non-blank lines in `habit_tips.txt` after both executions.

- [ ] **AC-22 — Phase 1 tests still pass unchanged.**
  `InsightPromptSpec` and the Phase 1 methods in `DoobieAnalyticsRepositorySpec`
  are not modified. `./gradlew test` reports no failures for Phase 1 test classes.

- [ ] **AC-23 — Phase 2 tests still pass unchanged.**
  `PromptBuilderSpec`, `DoobieAnalyticsRepositorySpec` (Phase 2 methods), and the
  Phase 2 integration test are not modified. `./gradlew test` reports no failures
  for Phase 2 test classes.

- [ ] **AC-24 — ./gradlew test passes in full with zero failures.**
  Running `./gradlew test` from the `backend/` directory produces zero test failures
  and zero compilation errors across all test classes (Phase 1, Phase 2, and Phase 3).
  Testcontainers specs annotated `@Ignore` are excluded from this count per the
  established convention.

---

## Out of scope

- User-written habit notes as a second retrieval source — this is Phase 4.
- Dynamic corpus updates — tips are seeded once from `habit_tips.txt`; no runtime
  corpus refresh endpoint.
- Re-embedding corpus tips at request time — embeddings are pre-computed and stored.
- Streaming responses — the Anthropic API is called in standard request/response mode.
- Frontend changes — no frontend work in this PBI.
- Authentication or user creation — `userId` in the path is trusted unconditionally.
- LangChain4j or any RAG framework — every step must be explicit Scala code.
- Changes to `AnthropicClient.scala`, `InsightPrompt.scala`, or the Phase 1/2
  endpoints or their tests.

---

## Technical notes for the Architect

### 1. pgvector setup — Docker Compose init script, not Liquibase

The `habit_tips` table and the `CREATE EXTENSION IF NOT EXISTS vector` statement
live in a Docker Compose init script (e.g. `docker/init.sql`), not in a Liquibase
changeset. Liquibase manages the application schema; pgvector setup is infrastructure.
ADR-010 must document this boundary.

### 2. New case classes — add to Analytics.scala with defaults

Add to `com.habittracker.model.Analytics`:

```scala
case class HabitTip(id: Long, content: String)

case class RetrievedTip(
  tip:             HabitTip,
  similarityScore: Double
)

case class TipsResponse(
  tips:      List[RetrievedTip],
  narrative: String
)
```

Extend `HabitContext` with:

```scala
retrievedTips: List[String] = Nil   // default for backward compat with frozen tests
```

Per the HabitContext Defaults Pattern (Architect Memory): the default is never
triggered in production; `DefaultAnalyticsService.buildHabitContext` always supplies
all fields by name.

### 3. EmbeddingClient — same structural pattern as AnthropicClient

`com.habittracker.client.EmbeddingClient` must be a no-trait, no-constructor Scala
`object`. Reads `OPENAI_API_KEY` from the environment. Startup failure is forced via:
  `Resource.eval(IO(EmbeddingClient.API_KEY_CHECK))` in `AppResources.make`.
`EmbeddingClient.embed[F[_]: Async](text: String): F[Vector[Float]]` — same
polymorphic signature pattern as `AnthropicClient.complete`, always called as
`EmbeddingClient.embed[IO](...)` at the call site.
Constants: `MODEL = "text-embedding-3-small"`, `API_URL = "https://api.openai.com/v1/embeddings"`,
`DIMENSION = 1536`.

### 4. TipRepository — uses IO directly, not F[_]

Per Architect Memory (Effect Type Convention): `TipRepository` must use `IO`
directly, not `F[_]: Async`. The phase brief shows `F[_]` in repository signatures —
this is documentation drift. Use `class TipRepository(xa: Transactor[IO])` with
`IO[_]` return types throughout.

### 5. PromptBuilder extension — additive, backward compatible

`PromptBuilder.build` gains a second parameter with default `Nil`:
`def build(ctx: HabitContext, tips: List[RetrievedTip] = Nil): String`.
The existing `AnalysisRoutes` call to `PromptBuilder.build(ctx)` compiles unchanged.
`retrievedContextSection(tips: List[RetrievedTip]): String` returns empty string
when tips is `Nil`, so `build` filters it out via `.filter(_.nonEmpty)`.
No existing section methods are modified.

### 6. Route wiring — TipsRoutes appended after AnalysisRoutes

Per Architect Memory (Route Registration Pattern), Phase 3 adds `TipsRoutes` after
`AnalysisRoutes` in `AppResources.make`:
```
... <+> new AnalysisRoutes(analyticsService).routes <+>
         new TipsRoutes(analyticsService, tipRepo).routes <+>
         new HabitRoutes(habitService).routes <+> ...
```
`OPENAI_API_KEY` force-init is added separately; the existing
`AnthropicClient.API_KEY_CHECK` must not be duplicated.

### 7. Circe codecs — add to AnalyticsCodecs

`HabitTip`, `RetrievedTip`, and `TipsResponse` encoders/decoders use semiauto
derivation and are added to `com.habittracker.http.AnalyticsCodecs`.
`UUID KeyEncoder`/`KeyDecoder` already exist there — do not redefine.

### 8. habit_tips.txt minimum requirements

At least 25 tips, one per line, covering all 10 topic areas listed in the Scope:
implementation intentions, habit stacking, environmental design, recovery,
tracking/measurement, intrinsic motivation, social accountability, identity,
sleep/energy, and habit breaking.

### 9. ADR-010 required

`docs/adr/ADR-010-phase3-basic-rag.md` must address:
- pgvector setup decision (init script vs Liquibase)
- EmbeddingClient structural design and startup key check
- TipRepository query approach (named val, pgvector `<=>` operator)
- How SeedTips integrates with the Gradle build (`runSeedTips` task)
- How the tips endpoint composes existing and new components
- Effect type correction (IO not F[_] in repositories)

### 10. LLM use case mapping

This PBI implements the **daily tip** LLM use case from CLAUDE.md:
> "given a habit + retrieved context from pgvector, generate a personalised tip
> for today. Always RAG-augmented — never a cold prompt."

---

## Dependencies

- PBI-014 (Phase 2) must be complete and all Done When items passing.
  `docs/phases/phase_2_review.md` must confirm readiness before Phase 3 starts.
- ADR-010 must be written and approved before the Developer agent starts.
- `AnthropicClient.scala`, `InsightPrompt.scala`, `InsightsRoutes.scala`,
  `AnalysisRoutes.scala`, and all Phase 1/2 test files are frozen carry-over
  contracts and must not be modified.
- `PromptBuilder.scala` IS extended in Phase 3 (additive only — new method,
  updated `build` signature with default args).

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
L

---
---

# PBI-016: Batch Habit Completions Endpoint (Backfill)

## User story

As the habit tracker application, I want a batch endpoint that accepts a list of
completion records and attempts each independently, so that callers can import
historical completions (backfill) without a single duplicate stopping the entire
import.

---

## Acceptance criteria

- [ ] **AC-1 — POST /users/{userId}/habits/completions/batch returns HTTP 200.**
  A POST request to `/users/{userId}/habits/completions/batch` with a valid
  `userId` (Long) and a well-formed JSON body returns HTTP 200. The response
  body is valid JSON in all cases — including when all items are skipped.

- [ ] **AC-2 — Request body accepts a JSON array of CreateHabitCompletionRequest items.**
  The request body is a JSON array where each element has the same shape as the
  single-completion request: `habitId` (UUID), `completedOn` (ISO date string),
  and optionally `note` (String) and `completedAt` (ISO instant string).
  A malformed body returns HTTP 400.

- [ ] **AC-3 — Response body deserialises to BatchCompletionResponse.**
  The JSON response deserialises to `BatchCompletionResponse` with exactly two
  fields: `inserted` (type `List[HabitCompletionResponse]`) and `skipped`
  (type `List[SkippedCompletion]`).

- [ ] **AC-4 — SkippedCompletion carries habitId, completedOn, and reason.**
  `SkippedCompletion` is a case class with fields:
  `habitId: UUID`, `completedOn: LocalDate`, `reason: String`.
  The `reason` field is a human-readable string describing why the item was
  skipped (e.g. "duplicate: habit already has a completion for this date",
  "not found: habit does not exist or is not active").

- [ ] **AC-5 — Each item is processed independently (partial success).**
  When a batch of 5 items contains 2 valid, 2 duplicates, and 1 referencing a
  non-existent habit, the response `inserted` list has 2 entries and the
  `skipped` list has 3 entries. The endpoint does not abort on the first failure.

- [ ] **AC-6 — Each item runs in its own transaction.**
  Each completion attempt is wrapped in its own database transaction. A failure
  (duplicate or habit-not-found) on one item does not roll back previously
  inserted items. A single wrapping transaction for the entire batch must not
  be used.

- [ ] **AC-7 — Duplicate items are skipped, not failed.**
  When an item would violate the UNIQUE constraint on `(habit_id, completed_on)`,
  it is routed to the `skipped` list with an appropriate `reason`. HTTP 200 is
  still returned. The constraint violation is not propagated as a 5xx error.

- [ ] **AC-8 — Habit-not-found items are skipped, not failed.**
  When an item references a `habitId` that does not exist or is soft-deleted for
  the given `userId`, it is routed to the `skipped` list with an appropriate
  `reason`. HTTP 200 is still returned.

- [ ] **AC-9 — HTTP 200 is returned even when all items are skipped.**
  A batch where every item is either a duplicate or references a non-existent
  habit returns HTTP 200 with `inserted: []` and `skipped: [<all items>]`.
  HTTP 207 or any non-200 status must not be used.

- [ ] **AC-10 — Existing POST /users/{userId}/habits/{habitId}/completions is unmodified.**
  The single-completion endpoint is not changed by this PBI. Its route pattern,
  request shape, response shape, and error behaviour are identical to Phase 2.
  Existing completion tests continue to pass.

- [ ] **AC-11 — OpenAPI spec updated.**
  `backend/src/main/resources/openapi/openapi.yaml` is updated to include:
  - `POST /users/{userId}/habits/completions/batch` operation
  - `BatchCompletionResponse` schema
  - `SkippedCompletion` schema
  The spec update is in the same PR as the implementation.

- [ ] **AC-12 — Unit test: partial success scenario.**
  A unit test (or service-level spec with a stubbed repository) verifies the
  partial success scenario: given a mix of valid, duplicate, and not-found items,
  the service returns `inserted` and `skipped` lists with the correct contents
  and counts.

- [ ] **AC-13 — Integration test: batch endpoint end-to-end.**
  An integration test (Testcontainers, annotated `@Ignore`) seeds a test user and
  two habits, then calls `POST /users/{userId}/habits/completions/batch` with:
  - 2 valid new completions
  - 1 duplicate of an already-inserted completion
  - 1 item referencing a non-existent habitId
  Asserts: HTTP 200, `inserted` count = 2, `skipped` count = 2, `reason` fields
  are non-empty strings.

- [ ] **AC-14 — ./gradlew test passes with zero failures.**
  `./gradlew test` from `backend/` produces zero failures and zero compilation
  errors, including all Phase 1, 2, and 3 test classes.

---

## Out of scope

- Streaming or chunked batch responses.
- Batch size limit or rate limiting — this is a PoC; no cap is enforced.
- Modifying the existing single-completion endpoint.
- Frontend changes.
- Authentication — `userId` is trusted unconditionally.
- Asynchronous / fire-and-forget batch processing — the endpoint is synchronous.

---

## Technical notes for the Architect

### 1. Route placement — new pattern under /users/{userId}/habits/completions/batch

The batch route must not conflict with the existing single-completion route
(`/users/{userId}/habits/{habitId}/completions`). The batch URL intentionally
omits `{habitId}` because a single batch request spans multiple habits.
Route precedence: add the batch pattern before the `UUIDVar(habitId)` pattern
in `HabitCompletionRoutes` to avoid any ambiguity in http4s path matching.

### 2. New DTOs

```scala
case class BatchCompletionResponse(
  inserted: List[HabitCompletionResponse],
  skipped:  List[SkippedCompletion]
)

case class SkippedCompletion(
  habitId:     UUID,
  completedOn: LocalDate,
  reason:      String
)
```

These DTOs are new. `HabitCompletionResponse` is reused unchanged.

### 3. Service method signature

Add to `HabitCompletionService` trait and `DefaultHabitCompletionService`:

```scala
def recordCompletionBatch(
    userId: Long,
    items:  List[CreateHabitCompletionRequest]
): IO[BatchCompletionResponse]
```

This method always returns `IO[BatchCompletionResponse]` (never
`IO[Either[AppError, ...]]`) because partial success is the contract —
there is no whole-batch failure mode.

### 4. Per-item transaction isolation

Each item is processed by calling the existing `completionRepo.create(completion)`
(which already wraps in a transaction via Doobie's `.transact`). The `recordCompletion`
service logic (habit ownership check + insert) can be reused per item. Do NOT wrap
the entire batch in one `transact` call.

### 5. Duplicate detection mechanism

`DoobieHabitCompletionRepository.create` already catches
`sqlstate.class23.UNIQUE_VIOLATION` and returns `Left(ConflictError(...))`. The
batch service maps `Left(ConflictError(...))` to a `SkippedCompletion` with a
duplicate reason. No new repository method is needed for duplicate detection.

### 6. Habit-not-found handling in batch context

The existing `habitRepo.findActiveById(userId, habitId)` returning `None` must also
produce a `SkippedCompletion` (not abort the batch). The batch service must handle
both the `None` (not found) path and the `Left(ConflictError)` (duplicate) path as
skip conditions.

### 7. Circe codecs

`BatchCompletionResponse` and `SkippedCompletion` codecs use semiauto derivation
and are added to `com.habittracker.http.CompletionCodecs` (same file as existing
completion codecs).

### 8. ADR-010 covers this PBI

The Architect need not produce a separate ADR for PBI-016. It is covered by
ADR-010-phase3-basic-rag.md, which should include a section on the batch endpoint
design decisions (partial success contract, per-item transaction, route placement).

---

## Dependencies

- PBI-017 (unique constraint verification) logically precedes this PBI because
  the batch skipped-list behaviour depends on the `ConflictError` produced by the
  repository's UNIQUE_VIOLATION handler. However, since the constraint and the
  `ConflictError` mapping already exist in the codebase (see Technical Notes §5),
  PBI-016 and PBI-017 can be implemented in the same phase.
- The existing `HabitCompletionRepository.create` contract must not change.

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
M

---
---

# PBI-017: One Completion Per Habit Per Day — Constraint Verification and Enforcement

## User story

As the habit tracker application, I want the one-completion-per-habit-per-day rule
enforced at the database level and surfaced as a 409 response from the single
endpoint and as a skip entry from the batch endpoint, so that duplicate completions
cannot be created regardless of concurrent callers or import tools.

---

## Acceptance criteria

- [ ] **AC-1 — UNIQUE constraint uq_habit_completions_habit_day already exists.**
  `SELECT constraint_name FROM information_schema.table_constraints WHERE table_name = 'habit_completions' AND constraint_type = 'UNIQUE'`
  returns a row with `constraint_name = 'uq_habit_completions_habit_day'`.
  No new migration is needed — the constraint was created in migration 002.
  This AC is a verification step, not an implementation step.

- [ ] **AC-2 — POST /users/{userId}/habits/{habitId}/completions returns 409 on duplicate.**
  Posting a completion for a `(habitId, completedOn)` pair that already exists
  returns HTTP 409 Conflict with a JSON body containing a non-empty `message` field.
  This behaviour is already implemented; this AC confirms it is tested and passing.

- [ ] **AC-3 — Service layer maps UNIQUE_VIOLATION to ConflictError.**
  `DoobieHabitCompletionRepository.create` catches PostgreSQL SQLSTATE `23505`
  (UNIQUE_VIOLATION) via `catchsql.attemptSomeSqlState` and returns
  `Left(ConflictError(...))`. `ErrorHandler.toResponse` maps `ConflictError` to
  HTTP 409. This chain is verified by an integration test that attempts to create
  two completions for the same habit on the same date.

- [ ] **AC-4 — Integration test for duplicate single endpoint returns 409.**
  A Testcontainers integration test (annotated `@Ignore`, requires Docker) seeds
  a habit and inserts a completion, then attempts to insert a second completion for
  the same `(habitId, completedOn)`. The test asserts the response status is 409
  and the response body deserialises to an `ErrorResponse` with a non-empty
  `message`.

- [ ] **AC-5 — Batch endpoint routes UNIQUE_VIOLATION to skipped list.**
  When `POST /users/{userId}/habits/completions/batch` contains an item that would
  violate the UNIQUE constraint, that item appears in `skipped` (not `inserted`)
  and its `reason` field contains the word "duplicate". The batch HTTP status is
  still 200.
  (This AC is satisfied by PBI-016 AC-7; it is repeated here to make the
  cross-PBI dependency explicit for the Reviewer.)

- [ ] **AC-6 — ./gradlew test passes with zero failures.**
  `./gradlew test` from `backend/` produces zero failures and zero compilation
  errors across all test classes.

---

## Out of scope

- Adding a new Liquibase migration for the UNIQUE constraint — the constraint
  already exists in migration 002 (`uq_habit_completions_habit_day`). No
  migration 006 is needed for this constraint.
- Composite unique constraints involving additional columns (e.g. user_id).
- Upsert behaviour — a duplicate is always rejected, never silently updated.
- Frontend changes.

---

## Technical notes for the Architect

### 1. IMPORTANT — constraint already exists, no new migration needed

The UNIQUE constraint `UNIQUE (habit_id, completed_on)` named
`uq_habit_completions_habit_day` was created in migration 002
(`infra/db/changelog/changesets/002-create-habit-completions-table.sql`).
**Do NOT create migration 006 for this constraint.** The task brief describes
adding it as a new migration, but inspection of the current schema shows it
already exists. The next available migration slot (006) should be reserved for
genuinely new schema changes in a future PBI.

### 2. Single endpoint 409 already implemented

`DoobieHabitCompletionRepository.create` already uses
`catchsql.attemptSomeSqlState` to catch `sqlstate.class23.UNIQUE_VIOLATION` and
returns `Left(ConflictError(...))`. `ErrorHandler.toResponse` already maps
`ConflictError` to `Conflict(...)` (HTTP 409). The service passes
`Left(ConflictError)` through to the route. The chain is complete.

The work in this PBI is:
- Verifying the integration test for the 409 path exists and passes (AC-3, AC-4).
- Documenting the constraint in ADR-010.
- Confirming the batch endpoint (PBI-016) correctly reuses this chain for its
  skip logic (AC-5).

### 3. ConflictError is already defined in AppError

`AppError.ConflictError(message: String)` exists in
`com.habittracker.domain.AppError`. No changes to the error hierarchy are needed.

### 4. ADR-010 must document this constraint

ADR-010 must include a section noting that the UNIQUE constraint predates Phase 3,
was created in migration 002, and is the mechanism that drives both the single-
endpoint 409 and the batch endpoint skipped list.

---

## Dependencies

- PBI-016 (batch completions) depends on the `ConflictError` mechanism verified
  here. PBI-017 should be reviewed before PBI-016 is implemented to confirm no
  new migration is needed.
- ADR-010 must be written before the Developer agent starts.

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
XS
