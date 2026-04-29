# PBI-018: Phase 4 — Full RAG Pipeline (Multi-Source Retrieval, Deduplication, Logging)

## User story

As the habit tracker application, I want the tips endpoint to retrieve context from
both a curated external corpus and the user's own historical notes in parallel, merge
and deduplicate the results, and log retrieval quality metadata, so that engineers can
see a production-shaped multi-source RAG pipeline with measurable quality and basic
observability.

---

## Acceptance criteria

> Items AC-1 through AC-22 map 1:1 to the Done When checklist in
> docs/phases/phase_4_full_rag.md (eval items removed per engineer decision 2026-04-29).
> AC-23 through AC-29 cover Scope items explicitly stated in the brief that are not
> in the Done When checklist.

### Database

- [ ] **AC-1 — user_notes table exists with vector(1536) column and user_id index.**
  After `docker compose -f infra/docker-compose.yml up -d`, the table `user_notes`
  exists with columns `id BIGSERIAL PRIMARY KEY`, `user_id BIGINT NOT NULL`,
  `content TEXT NOT NULL`, `embedding vector(1536) NOT NULL`,
  `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
  A separate index `user_notes_user_id_idx ON user_notes(user_id)` exists.
  The table is created by the Docker Compose init SQL (infrastructure layer), not by
  a Liquibase changeset.

### POST /users/{userId}/habits/notes

- [ ] **AC-2 — POST /users/{userId}/habits/notes returns HTTP 201 with UserNote body.**
  A POST request with a valid JSON body `{"content": "<text>"}` returns HTTP 201.
  The response body deserialises to `UserNote` with fields:
  `id: Long`, `userId: Long`, `content: String`, `createdAt: java.time.Instant`.

- [ ] **AC-3 — Stored note has a non-null embedding in the database.**
  After a successful POST, the corresponding row in `user_notes` has a non-null,
  non-zero `embedding` column. The embedding dimension is 1536, consistent with
  `text-embedding-3-small`. The embedding is produced by `EmbeddingClient.embed`
  at request time.

### GET /users/{userId}/habits/tips (modified)

- [ ] **AC-4 — GET /users/{userId}/habits/tips returns HTTP 200 with TipsResponse.**
  A GET request to `/users/{userId}/habits/tips` with a valid `userId` (Long)
  returns HTTP 200. The response body is valid JSON.

- [ ] **AC-5 — TipsResponse has externalTips and personalNotes fields (breaking change — not a single tips field).**
  **This is a breaking change from Phase 3.** The JSON response deserialises to
  `TipsResponse` with exactly three fields:
  - `externalTips: List[RetrievedTip]` — results from the `habit_tips` corpus (Phase 3 source)
  - `personalNotes: List[RetrievedTip]` — results from `user_notes` (Phase 4 source)
  - `narrative: String`
  The old `tips` field no longer exists. All existing tests that reference
  `TipsResponse.tips` must be updated to `TipsResponse.externalTips`. This update
  is explicitly required and allowed — it is the only category of Phase 3 test that
  must change.

- [ ] **AC-6 — After POST /notes, GET /tips returns that note in personalNotes.**
  Given a POST to `/users/{userId}/habits/notes` with content `"X"` followed by a
  GET to `/users/{userId}/habits/tips`, the content `"X"` (or its semantically
  retrieved form) appears in the `personalNotes` list of the response.

- [ ] **AC-7 — Parallel retrieval uses parTupled — visible in code.**
  `TipsRoutes` (or the service called from it) contains a `def retrieveBoth` that
  accepts `(queryEmbedding: Vector[Float], userId: Long)` as parameters and uses
  `(io1, io2).parTupled` from `cats.syntax.parallel._`. The two IOs are
  `TipRepository.findSimilar` and `NoteRepository.findSimilar`. Both `GET /tips` and
  any future caller invoke `retrieveBoth` directly. Sequential chaining (`.flatMap`)
  must not be used for these two calls.

- [ ] **AC-8 — Inline comment on parallel retrieval explains why parallel is used.**
  Immediately before or within the `retrieveBoth` definition, there is an inline
  comment explaining: (1) both retrievals are independent IO operations with no
  shared state; (2) `parTupled` runs them concurrently, halving retrieval latency
  vs sequential; (3) Cats IO `parTupled` does this without blocking on the same
  thread pool. The comment is a genuine explanation, not a restatement of the code.

### Deduplication

- [ ] **AC-9 — deduplicate is a pure function in Deduplication.scala.**
  `com.habittracker.service.Deduplication` is a Scala `object` in
  `src/main/scala/service/Deduplication.scala` (maps to `com/habittracker/service/`).
  The `deduplicate` function has signature
  `def deduplicate(tips: List[RetrievedTip], notes: List[RetrievedTip]): (List[RetrievedTip], List[RetrievedTip])`.
  It contains no `F[_]`, no `IO`, and no `Async` constraint. It is a pure
  referentially transparent function.

- [ ] **AC-10 — All 6 deduplication unit tests pass.**
  The following pure unit tests (no IO, no Docker, no `@Ignore`) exist in a
  `DeduplicationSpec` and pass with `./gradlew test`:
  - `deduplicate`: identical content in both lists — only one item retained.
  - `deduplicate`: no overlap between lists — both lists returned unchanged.
  - `deduplicate`: higher-scored duplicate is retained, lower-scored removed.
  - `wordOverlapRatio`: known inputs produce the expected ratio.
  - `isDuplicate`: pair within both thresholds (score diff < 0.05 and word overlap > 0.8) returns `true`.
  - `isDuplicate`: pair outside either threshold returns `false`.

### Named constants

- [ ] **AC-11 — TIPS_TOP_K and NOTES_TOP_K are named constants.**
  The values passed as `topK` to `TipRepository.findSimilar` and
  `NoteRepository.findSimilar` respectively are bound to named constants:
  `val TIPS_TOP_K: Int = 2` and `val NOTES_TOP_K: Int = 2`.
  Magic number literals `2` must not appear at the call sites.

### PromptBuilder

- [ ] **AC-12 — PromptBuilder.personalNotesSection uses "YOUR PAST NOTES:" label.**
  `PromptBuilder.personalNotesSection(notes: List[RetrievedTip]): String`
  returns a string whose header is `"YOUR PAST NOTES:"` when `notes` is non-empty.
  This label is distinct from `"RELEVANT TIPS:"` used by `retrievedContextSection`.

- [ ] **AC-13 — PromptBuilder.build accepts notes with default Nil (backward compatible).**
  `PromptBuilder.build` has the updated signature:
  `def build(ctx: HabitContext, tips: List[RetrievedTip] = Nil, notes: List[RetrievedTip] = Nil): String`.
  Calling `build(ctx)` or `build(ctx, tips)` compiles without modification.
  The existing `AnalysisRoutes` call site compiles unchanged (regression guard).

### Logging

- [ ] **AC-14 — RagLogger logs scores and counts only — no content logged.**
  `com.habittracker.observability.RagLogger` (in `src/main/scala/observability/RagLogger.scala`)
  contains `logRetrieval[F[_]: Async](userId: Long, tips: List[RetrievedTip], notes: List[RetrievedTip]): F[Unit]`.
  Log output format: `RAG userId=X externalCount=N personalCount=M topExternalScore=0.87 topPersonalScore=0.91`.
  The `content` field of any `RetrievedTip` is never written to any log statement.
  Output is to stdout only (via `Async[F].delay(println(...))`).

### NoteRepository

- [ ] **AC-15 — NoteRepository has a named similaritySearchSql val.**
  `com.habittracker.repository.NoteRepository` declares the pgvector similarity
  search query as a named `val` called `similaritySearchSql` (same constraint as
  `TipRepository.similaritySearchSql`). The SQL is not an anonymous string literal
  at the call site.

- [ ] **AC-16 — NoteRepository filters by userId — verified by test.**
  `NoteRepository.findSimilar(userId, queryEmbedding, topK)` returns only results
  where `user_id = userId`. This is verified by a Testcontainers test that inserts
  notes for two different user IDs and asserts that a query for one `userId` returns
  no results belonging to the other user.

### NoteRepository tests

- [ ] **AC-17 — All 4 NoteRepository Testcontainers tests pass when run manually.**
  The following tests exist in a `NoteRepositorySpec` (annotated `@Ignore`, requires Docker,
  uses `DockerImageName.parse("pgvector/pgvector:pg17")`):
  - `insert`: stores a note with embedding; returns `UserNote` with a non-null generated `id`.
  - `findSimilar (topK)`: returns exactly `topK` results ordered by `similarityScore` descending.
  - `findSimilar (userId filter)`: query for user A does not return notes belonging to user B.
  - `findSimilar (empty table)`: empty `user_notes` table returns empty list without error.

### Parallel retrieval test

- [ ] **AC-18 — Parallel retrieval test verifies both repository calls are made.**
  An IO unit test (using mocked/stubbed `TipRepository` and `NoteRepository`) calls
  `retrieveBoth(queryEmbedding, userId)` and asserts that both
  `TipRepository.findSimilar` and `NoteRepository.findSimilar` are invoked exactly
  once. The test uses ScalaTest `AnyWordSpec` with `@RunWith(classOf[JUnitRunner])`.

### Integration tests

- [ ] **AC-19 — Integration test: POST /notes then GET /tips — note appears in personalNotes.**
  A Testcontainers integration test (annotated `@Ignore`) seeds a user, calls
  `POST /users/{userId}/habits/notes`, then calls `GET /users/{userId}/habits/tips`
  and asserts: HTTP 200, `personalNotes` is non-empty, the response deserialises
  without error.

### AppResources wiring

- [ ] **AC-20 — AppResources includes NoteRepository and NoteRoutes.**
  `AppResources.make` constructs a `NoteRepository(xa)` inside its `Resource`
  for-comprehension and passes it to `NoteRoutes` (a new class in
  `com.habittracker.http`, separate from `TipsRoutes`). POST /notes is handled
  by `NoteRoutes`, not by `TipsRoutes`. The existing route composition order is
  preserved:
  `DocsRoutes → InsightsRoutes → AnalysisRoutes → TipsRoutes → BatchCompletionRoutes
   → NoteRoutes → HabitRoutes → HabitCompletionRoutes`.

### Regression guards

- [ ] **AC-21 — Phase 1 and Phase 2 tests pass unchanged.**
  `InsightPromptSpec`, `DoobieAnalyticsRepositorySpec` (Phase 1 and Phase 2 methods),
  and the Phase 2 integration test are not modified. `./gradlew test` reports zero
  failures for Phase 1 and Phase 2 test classes.

- [ ] **AC-22 — Phase 3 tests pass (TipsResponse tests updated for new field names).**
  All Phase 3 test classes pass. The only permitted modification to Phase 3 test files
  is renaming references from `TipsResponse.tips` to `TipsResponse.externalTips`.
  No other Phase 3 test logic is changed.

- [ ] **AC-23 — ./gradlew test passes in full with zero failures.**
  Running `./gradlew test` from the `backend/` directory produces zero test failures
  and zero compilation errors across all test classes (Phase 1, 2, 3, and 4).
  Testcontainers specs annotated `@Ignore` are excluded from this count per the
  established convention.

---

### Additional acceptance criteria from Scope (not in Done When checklist)

- [ ] **AC-24 — New case classes added to Analytics.scala.**
  `com.habittracker.model.Analytics` contains two new case classes:
  `UserNote(id: Long, userId: Long, content: String, createdAt: java.time.Instant)`,
  `NoteRequest(content: String)`.
  Circe semiauto encoders/decoders for both are added to `AnalyticsCodecs`.

- [ ] **AC-25 — NoteRepository uses IO directly, not F[_]: Async.**
  `NoteRepository` is declared as `class NoteRepository(xa: Transactor[IO])` with
  `IO[UserNote]` and `IO[List[RetrievedTip]]` return types. The phase brief shows
  `F[_]` in repository signatures — this is documentation drift. The actual
  implementation must follow the Effect Type Convention established in prior phases
  (same as `TipRepository`).

- [ ] **AC-26 — deduplicate algorithm uses the specified thresholds.**
  Two items are considered duplicates if and only if their similarity scores differ
  by less than 0.05 AND their word overlap ratio exceeds 0.8 (where
  `overlap = sharedWords / max(wordsA.size, wordsB.size)`). When a duplicate pair
  is found, the item with the higher score is retained.

- [ ] **AC-27 — PromptBuilder.personalNotesSection returns empty string for empty input.**
  Calling `PromptBuilder.personalNotesSection(Nil)` returns an empty string `""`.
  The `build` method filters it out via `.filter(_.nonEmpty)`, so the output of
  `build(ctx, tips = Nil, notes = Nil)` matches the Phase 2 `build(ctx)` output
  for the same `ctx` (regression guard for Phase 2 call sites).

- [ ] **AC-28 — All new sttp HTTP calls set Content-Type after .body() with replaceExisting = true.**
  Any new sttp request in Phase 4 (if applicable) sets the `Content-Type` header
  AFTER the `.body(...)` call using
  `.header("Content-Type", "application/json", replaceExisting = true)`.
  This prevents the Phase 3 production bug where `.body()` silently overrides a
  Content-Type set before it. `AnthropicClient` and `EmbeddingClient` are frozen
  and excluded from this requirement.

- [ ] **AC-29 — Post-Phase 4 notes file created.**
  A file `docs/future_improvements.md` is created containing notes on the five
  future improvement areas specified in the brief: chunking, embedding cache,
  token budget, eval persistence, and framework introduction point.
  Content must match the substance of the brief's POST-PHASE 4 NOTES section.

---

## Out of scope

- LangChain4j or any RAG framework — the pipeline is hand-rolled Scala.
- Re-ranking models or external reranking APIs.
- Streaming responses — all endpoints are standard request/response.
- Frontend changes — no frontend work in this PBI.
- Authentication — `userId` in the path is trusted unconditionally.
- Chunking long notes before embedding — noted in `docs/future_improvements.md` but not implemented.
- Embedding cache — noted in `docs/future_improvements.md` but not implemented.
- Token budget enforcement on assembled prompts — noted in `docs/future_improvements.md` but not implemented.
- RAG evaluation endpoint (POST /users/{userId}/habits/tips/evaluate) — removed per engineer decision 2026-04-29.
- Changes to `AnthropicClient.scala`, `EmbeddingClient.scala`, `InsightPrompt.scala`,
  `InsightsRoutes.scala`, `AnalysisRoutes.scala`, or any Phase 1/2 test file (except
  TipsResponse field rename in Phase 3 test files).
- New Liquibase migrations — `user_notes` lives in the Docker Compose init SQL
  (infrastructure boundary from ADR-010). The Liquibase 006 slot remains reserved.
- Embedding model changes — `text-embedding-3-small` (1536 dimensions) is unchanged.

---

## Technical notes for the Architect

### 1. TipsResponse is a breaking change — flagged explicitly

`TipsResponse` changes from `(tips: List[RetrievedTip], narrative: String)` to
`(externalTips: List[RetrievedTip], personalNotes: List[RetrievedTip], narrative: String)`.
This is a **breaking change**. All existing tests that reference `TipsResponse.tips`
must be updated to `TipsResponse.externalTips`. This is explicitly required and allowed;
it is the only Phase 3 test category that must change.
`TipsRoutes` is modified in Phase 4 (parallel retrieval replaces sequential) — it is
NOT frozen. This must be stated clearly in ADR-011.

### 2. NoteRepository effect type — use IO, not F[_]: Async

The phase brief shows `F[_]: Async` in NoteRepository signatures. This is documentation
drift — the same drift that affected TipRepository in Phase 3. Per the Effect Type
Convention established in prior phases: `class NoteRepository(xa: Transactor[IO])` with
`IO[_]` return types throughout. Do not propose `F[_]` in repository, service, or route
signatures.

### 3. PromptBuilder.build signature extension

New signature: `def build(ctx: HabitContext, tips: List[RetrievedTip] = Nil, notes: List[RetrievedTip] = Nil): String`.
The default args for both `tips` and `notes` make this backward compatible. The
`AnalysisRoutes` call `PromptBuilder.build(ctx)` compiles unchanged.

### 4. NoteRepository.similaritySearchSql must be a named val

Same constraint as `TipRepository.similaritySearchSql` (AC-12 in PBI-015): the pgvector
similarity search query in `NoteRepository` must be a named `val`, not an anonymous
string literal at the call site.

### 5. sttp Content-Type header order (CRITICAL — Phase 3 production bug)

All new sttp HTTP calls must set `Content-Type` AFTER `.body()` with `replaceExisting = true`:
```scala
basicRequest
  .post(uri"$url")
  .header("Authorization", s"Bearer $key")
  .body(bodyJson)
  .header("Content-Type", "application/json", replaceExisting = true)  // ← after body
  .response(asString)
```
In sttp v3, `.body(string)` sets `Content-Type: text/plain; charset=utf-8` internally.
A Content-Type set before `.body()` is silently overridden (default `replaceExisting=false`).
`AnthropicClient` and `EmbeddingClient` are frozen — their existing pattern is accepted
as a known trade-off. All new callers must follow the correct pattern.

### 6. ADR-011 required

`docs/adr/ADR-011-phase4-full-rag.md` must address:
- NoteRepository design (IO vs F[_], named SQL val, userId filtering)
- Parallel retrieval approach (parTupled rationale, latency benefit)
- Deduplication algorithm justification (thresholds, pure function requirement)
- Logging strategy (scores/counts only, never content, stdout)
- TipsResponse breaking change migration approach
- Route registration order (NoteRoutes placement, separation from TipsRoutes)

### 7. LLM use case mapping

Phase 4 extends the **daily tip** LLM use case from CLAUDE.md:
> "given a habit + retrieved context from pgvector, generate a personalised tip
> for today. Always RAG-augmented — never a cold prompt."
Phase 4 makes this multi-source (external corpus + personal notes).

### 8. Circe codecs

`UserNote` and `NoteRequest` encoders/decoders use semiauto derivation and are added
to `com.habittracker.http.AnalyticsCodecs`.
`TipsResponse` encoder/decoder must be updated to reflect the field rename.

### 9. retrieveBoth is a def with parameters

`retrieveBoth` must be declared as a `def` accepting `(queryEmbedding: Vector[Float], userId: Long)`
so both `GET /tips` and any future caller can invoke it directly. It must not be a `val`
or a zero-argument def that closes over request-scoped state.

### 10. NoteRoutes is a separate class

`POST /users/{userId}/habits/notes` is handled by a new `NoteRoutes` class in
`com.habittracker.http`. It is not added to `TipsRoutes`. `AppResources` wires
`NoteRoutes` separately after `BatchCompletionRoutes` in the route composition.

---

## Dependencies

- PBI-017 (Phase 3 unique constraint verification) is complete.
  `docs/phases/phase_3_review.md` confirms APPROVED — no blocking issue.
- ADR-011 must be written and approved before the Developer agent starts.
- `AnthropicClient.scala`, `EmbeddingClient.scala`, `InsightPrompt.scala`,
  `InsightsRoutes.scala`, and `AnalysisRoutes.scala` are frozen carry-over contracts.
- `TipsRoutes.scala` and `PromptBuilder.scala` ARE modified in Phase 4.
- Phase 1 and Phase 2 test files are frozen (no changes permitted).
- Phase 3 test files are frozen except for the TipsResponse field rename
  (`tips` → `externalTips`).

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
L
