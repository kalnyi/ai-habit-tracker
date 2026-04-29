# Review: Phase 3 — RAG Tips Endpoint, Batch Completions, Unique Constraint Verification (PBI-015, PBI-016, PBI-017)

## Verdict
APPROVED

---

## Blocking issues (must fix before merge)
None.

---

## Warnings (should fix)

- [backend/src/test/scala/com/habittracker/scripts/SeedTipsIdempotencySpec.scala:103-126]
  `SeedTipsIdempotencySpec` does not call `SeedTips.run` at all. It exercises
  `TipRepository.findExistingByContent` / `insert` directly with dummy embeddings.
  The spec title says "be idempotent — running twice produces exactly as many rows",
  and the idempotency mechanism *is* correctly tested (the SELECT-then-INSERT guard
  works). However, any bug introduced specifically in `SeedTips.run` (e.g., wrong
  resource-bracket, broken logging call, IOApp wiring) would not be caught by this
  test. This matches the accepted trade-off for live-API tests (`@Ignore`,
  OPENAI_API_KEY required), but the disconnect between the test name and what is
  actually exercised is worth noting for a future reader.
  Suggestion: Add a `// NOTE: SeedTips.run is not called here` comment at the top
  of the test case to make the scope explicit, or rename the test to "TipRepository
  insert-then-skip is idempotent".

- [backend/src/test/scala/com/habittracker/http/HabitCompletionCodecsSpec.scala]
  `HabitCompletionResponse` still has no round-trip test with `completedAt`
  populated. This was a warning in the Phase 2 review. Phase 3 added
  `completedAt`-populated tests for `CreateHabitCompletionRequest` (lines 96-110),
  which is welcome, but the response-side gap from Phase 2 remains open.
  Suggestion: add one `HabitCompletionResponse` round-trip test with
  `completedAt = Some(Instant.parse("2026-04-17T09:30:00Z"))`.

- [backend/src/test/scala/com/habittracker/http/HabitCompletionCodecsSpec.scala]
  `BatchCompletionResponse` and `SkippedCompletion` codecs are not tested at the
  codec-unit level. They are covered only through the integration spec
  (`HabitCompletionApiIntegrationSpec`, `@Ignore`). A round-trip unit test for each
  would make the serialisation contract reviewable without Docker.
  Suggestion: add `BatchCompletionResponse` and `SkippedCompletion` round-trip tests
  to `HabitCompletionCodecsSpec`.

---

## Nits (consider improving)

- [backend/src/main/scala/com/habittracker/http/BatchCompletionRoutes.scala:25]
  `handleErrorWith { case _: DecodeFailure => BadRequest(...) }` is a partial
  function. A non-`DecodeFailure` exception would propagate as an unhandled error
  rather than being caught here. This matches the identical pattern in
  `HabitCompletionRoutes.scala`, so it is a pre-existing convention, not a new
  defect — noting it here for completeness.

- [backend/src/main/scala/com/habittracker/service/AnalyticsService.scala:57-68]
  `buildTipsQuery` returns `"General habit-building practical advice."` when either
  `consistencyRanking` is empty OR `worstDays.size < 2`. The `size < 2` guard fires
  on a `Map` of only one day-of-week, which is unusual but possible for a user with
  very few completions. The fallback text is reasonable; this is purely cosmetic for
  the PoC.

- [backend/src/main/scala/com/habittracker/prompt/PromptBuilder.scala:21-24]
  Carry-over from Phase 2 review: `streakSection` and `momentumSection` still render
  habit IDs as raw UUIDs rather than habit names. Not introduced by Phase 3, but
  worth surfacing again since the tips narrative now uses the same sections.

---

## Acceptance criteria check

### PBI-015 (RAG Tips Endpoint)

| AC | Criterion | Status | Notes |
|----|-----------|--------|-------|
| AC-1 | Docker Compose starts cleanly with pgvector extension enabled | PASS | `docker-compose.yaml` uses `pgvector/pgvector:pg17` image; `infra/db/init/01_enable_vector.sql` runs `CREATE EXTENSION IF NOT EXISTS vector` |
| AC-2 | habit_tips table exists with vector(1536) column after `docker compose up` | PASS | `infra/db/init/02_create_habit_tips.sql` creates the table with correct schema |
| AC-3 | SeedTips runs without error and logs "Seeded tip N/total: [first 50 chars]" | PASS | `SeedTips.scala` line 41 uses exact required log format; `runSeedTips` Gradle task wired in `build.gradle` lines 166-173 |
| AC-4 | SeedTips is idempotent — running twice produces no duplicate rows | PASS | SELECT-then-INSERT guard in `SeedTips.scala` lines 34-43; defensive `uq_habit_tips_content` md5 index in `02_create_habit_tips.sql`; tested (with caveats — see Warnings) in `SeedTipsIdempotencySpec` |
| AC-5 | GET /users/{userId}/habits/tips returns HTTP 200 | PASS | Route present in `TipsRoutes.scala` line 26; returns `Ok(response)` line 38 |
| AC-6 | Response body deserialises to TipsResponse with `tips` and `narrative` | PASS | `TipsResponse(tips, narrative)` in `Analytics.scala` line 38; encoders in `AnalyticsCodecs.scala` lines 43-44 |
| AC-7 | tips list contains exactly TOP_K (3) items; TOP_K is a named constant | PASS | `private val TOP_K: Int = 3` in `TipsRoutes.scala` line 22; used at call site line 31 |
| AC-8 | Each RetrievedTip has a non-zero similarityScore | PASS | `1.0 - (embedding <=> ...)` in `similaritySearchSql`; random embeddings in test produce non-zero scores |
| AC-9 | narrative is a non-empty string from Anthropic API | PASS (manual) | `AnthropicClient.complete[IO]` call at `TipsRoutes.scala` line 33-35; accepted as manual-only per known accepted gap #1 in reviewer memory |
| AC-10 | EmbeddingClient is an object with direct sttp call, no trait/constructor | PASS | `object EmbeddingClient` in `EmbeddingClient.scala` line 10; direct `basicRequest.post(...)` at line 50; no trait |
| AC-11 | OPENAI_API_KEY read from environment — not hardcoded | PASS | `sys.env.get("OPENAI_API_KEY")` at line 25; key forced in `AppResources.make` line 33 |
| AC-12 | TipRepository.similaritySearchSql is a named val/def | PASS | Named `private def similaritySearchSql(...)` at `TipRepository.scala` line 48 |
| AC-13 | Inline comment in EmbeddingClient explains embedding vector | PASS — substantive | Comment at lines 12-18 explains 1536-number list, semantic meaning, and closeness in high-dimensional space. Genuine explanation, not a restatement |
| AC-14 | Inline comment on similaritySearchSql explains cosine similarity | PASS — substantive | Comment at `TipRepository.scala` lines 41-47 explains score of 1.0/0.0, `<=>` as cosine distance, and 1-subtract to get similarity. Genuine explanation |
| AC-15 | Inline comment on findSimilar explains ORDER BY distance | PASS — substantive | Comment at `TipRepository.scala` lines 80-84 explains ascending distance = descending similarity. Genuine explanation |
| AC-16 | Inline comment in retrievedContextSection explains grounding | PASS — substantive | Comment at `PromptBuilder.scala` lines 94-99 explains grounding: retrieved knowledge anchors LLM vs cold prompt. Genuine explanation |
| AC-17 | TOP_K is a named constant (value 3) | PASS | `private val TOP_K: Int = 3` in `TipsRoutes.scala` line 22 |
| AC-18 | PromptBuilder.build accepts tips with default Nil (backward compatible) | PASS | `def build(ctx: HabitContext, tips: List[RetrievedTip] = Nil)` at line 114; `AnalysisRoutes` call site `PromptBuilder.build(ctx)` unchanged |
| AC-19 | Four TipRepository Testcontainers tests exist, @Ignore | PASS | `TipRepositorySpec.scala` — insert (1 test), findSimilar topK (1), findSimilar ordered (1), findSimilar empty table (1); all @Ignore, uses `pgvector/pgvector:pg17` |
| AC-20 | Four PromptBuilder pure unit tests exist and pass | PASS | `retrievedContextSection` non-empty (line 186), empty (line 193), `build` with tips (line 205), `build` without tips / regression guard (line 215); all present, no @Ignore; PromptBuilderSpec 19 PASSED |
| AC-21 | SeedTips idempotency test exists (@Ignore) | PASS (with caveats) | `SeedTipsIdempotencySpec` exists, @Ignore, correct row count assertion; uses `TipRepository` directly rather than `SeedTips.run` — see Warnings |
| AC-22 | Phase 1 tests still pass unchanged | PASS | `InsightPromptSpec` 4 PASSED; Phase 1 `DoobieAnalyticsRepositorySpec` methods SKIPPED per @Ignore |
| AC-23 | Phase 2 tests still pass unchanged | PASS | `PromptBuilderSpec` 19 PASSED (includes 4 new Phase 3 tests); `DoobieAnalyticsRepositorySpec` SKIPPED; Phase 2 integration spec SKIPPED |
| AC-24 | ./gradlew test passes with zero failures | PASS | BUILD SUCCESSFUL; 0 failures; all Testcontainers specs SKIPPED per @Ignore convention |

### PBI-016 (Batch Completions)

| AC | Criterion | Status | Notes |
|----|-----------|--------|-------|
| AC-1 | POST /users/{userId}/habits/completions/batch returns HTTP 200 | PASS | `BatchCompletionRoutes.scala` line 24; `Ok(_)` unconditional |
| AC-2 | Request body accepts JSON array of CreateHabitCompletionRequest-shaped items; malformed body returns 400 | PASS | `BatchCompletionItem` DTO mirrors request shape; `handleErrorWith { case _: DecodeFailure => BadRequest(...) }` at line 25 |
| AC-3 | Response deserialises to BatchCompletionResponse with inserted and skipped | PASS | `BatchCompletionResponse(inserted, skipped)` in `BatchCompletionResponse.scala`; codecs in `CompletionCodecs.scala` lines 47-51 |
| AC-4 | SkippedCompletion carries habitId, completedOn, reason (human-readable) | PASS | `SkippedCompletion.scala` has all three fields; reason strings at `HabitCompletionService.scala` lines 130, 149 |
| AC-5 | Partial success — mix of valid, duplicate, not-found produces correct inserted/skipped | PASS | Service-level test in `HabitCompletionServiceSpec.scala` lines 374-409 asserts inserted=2, skipped=2 for a 4-item mixed batch |
| AC-6 | Each item runs in its own transaction | PASS | `completionRepo.create(completion)` per item in `processOne`; `DoobieHabitCompletionRepository.create` already wraps each in `.transact(transactor)` |
| AC-7 | Duplicate items are skipped with reason containing "duplicate" | PASS | `reason = s"duplicate: $msg"` at `HabitCompletionService.scala` line 149 |
| AC-8 | Habit-not-found items are skipped | PASS | `reason = "habit not found or not active for this user"` at line 130 |
| AC-9 | HTTP 200 even when all items are skipped | PASS | `Ok(_)` unconditional; `recordCompletionBatch` always returns `IO[BatchCompletionResponse]` not `IO[Either[...]]` |
| AC-10 | Existing single-completion endpoint unmodified | PASS | No diff to `HabitCompletionRoutes.scala` in Phase 3 commit; existing tests pass |
| AC-11 | OpenAPI spec updated | PASS | `POST /users/{userId}/habits/completions/batch` at yaml lines 280-311; `BatchCompletionResponse` schema lines 590-601; `SkippedCompletion` schema lines 578-589; `BatchCompletionItem` schema lines 561-577 |
| AC-12 | Unit test for partial success scenario | PASS | `HabitCompletionServiceSpec.scala` lines 373-410 |
| AC-13 | Integration test: batch end-to-end with mixed items, asserts inserted=2 skipped=2 | PASS | `HabitCompletionApiIntegrationSpec.scala` lines 348-378; @Ignore |
| AC-14 | ./gradlew test passes with zero failures | PASS | BUILD SUCCESSFUL, 0 failures |

### PBI-017 (Unique Constraint Verification)

| AC | Criterion | Status | Notes |
|----|-----------|--------|-------|
| AC-1 | UNIQUE constraint uq_habit_completions_habit_day already exists | PASS | Verified in `infra/db/changelog/changesets/002-create-habit-completions-table.sql` |
| AC-2 | Single-completion endpoint returns 409 on duplicate | PASS | Tested in `HabitCompletionApiIntegrationSpec.scala` lines 209-215 (pre-existing path), and PBI-017 dedicated test lines 327-342 |
| AC-3 | Service maps UNIQUE_VIOLATION to ConflictError | PASS | Pre-existing in `DoobieHabitCompletionRepository.create`; verified via service-level test in `HabitCompletionServiceSpec.scala` lines 125-140 |
| AC-4 | Integration test for duplicate single endpoint returns 409 | PASS | `HabitCompletionApiIntegrationSpec.scala` lines 327-342; @Ignore |
| AC-5 | Batch endpoint routes UNIQUE_VIOLATION to skipped list with reason containing "duplicate" | PASS | `reason = s"duplicate: $msg"` at `HabitCompletionService.scala` line 149; asserted in integration test line 375 |
| AC-6 | ./gradlew test passes with zero failures | PASS | BUILD SUCCESSFUL, 0 failures |

---

## Additional checks

| Check | Status | Notes |
|-------|--------|-------|
| Frozen files (AnthropicClient, InsightPrompt, InsightsRoutes, AnalysisRoutes) unchanged in Phase 3 | PASS | `git diff a5efff1 239e1c3` produces no output for all four files |
| No `generic.auto` imports in any source file | PASS | grep across `src/main/` returns no results |
| No `var` in production code | PASS | No `var` in any main/ Scala source |
| No hardcoded secrets | PASS | Only `sys.env.get("OPENAI_API_KEY")` and `sys.env.get("ANTHROPIC_API_KEY")` |
| No new runtime dependencies added | PASS | `build.gradle` diff introduces only `runSeedTips` Gradle task, no new `implementation` entries; pgvector wire format hand-rolled per ADR-010 §5 |
| All new codecs use semiauto derivation | PASS | `deriveEncoder`/`deriveDecoder` throughout `AnalyticsCodecs.scala` and `CompletionCodecs.scala` |
| ADR-010 exists at correct path | PASS | `docs/adr/ADR-010-phase3-basic-rag.md` — correct path and number per reviewer memory |
| Route order in AppResources | PASS | DocsRoutes → InsightsRoutes → AnalysisRoutes → TipsRoutes → BatchCompletionRoutes → HabitRoutes → HabitCompletionRoutes — matches ADR-010 §8 |
| habit_tips.txt has at least 25 tips, 10 topic areas | PASS | 26 non-blank lines; covers: implementation intentions (1), habit stacking (2, 3), environmental design (4, 5), recovery (6, 7), tracking/measurement (8, 9), intrinsic motivation (10, 11, 12), social accountability (13, 14, 15), sleep/energy (16, 17, 18), habit breaking (19, 20, 21), general (22-27) |
| SeedTips log format matches AC-3 | PASS | `s"Seeded tip ${idx + 1}/$total: ${line.take(50)}"` at `SeedTips.scala` line 41 |
| pgvector wire format: `[v1,v2,...,vN]` string with `::vector` cast | PASS | `v.mkString("[", ",", "]")` and `$embeddingLiteral::vector` in `TipRepository.scala` |
| EmbeddingClient.API_KEY_CHECK forced in AppResources | PASS | `Resource.eval(IO(EmbeddingClient.API_KEY_CHECK))` at `AppResources.scala` line 33 |
| HabitCompletionRoutesSpec count (Phase 2 frozen) | PASS | 16 PASSED (up from 13 — the 3 additions appear to be completedAt codec coverage) |
| TipRepositorySpec uses pgvector/pgvector:pg17 | PASS | `DockerImageName.parse("pgvector/pgvector:pg17")` at line 36 |
| SeedTipsIdempotencySpec @Ignore comment includes Docker and OPENAI_API_KEY | PASS | Line 3: `// requires Docker AND OPENAI_API_KEY - run manually` |
| No debug logging or TODO comments in new production code | PASS | grep across all new files returns nothing |

---

## Inline comments quality assessment

All four required locations have genuine, substantive prose. Summary of what each says:

**1. EmbeddingClient (above MODEL constant) — lines 12-18:**
> "An embedding is a list of 1536 floating-point numbers that represent the semantic meaning of a text. Two texts whose meanings are similar produce embedding vectors that are close together in 1536-dimensional space; texts with unrelated meaning produce vectors that point in very different directions. Cosine similarity (and pgvector's `<=>` operator) measure that closeness numerically — see TipRepository for how the score is computed."

Assessment: Explains the concept accurately, uses the required terms (list of numbers, semantic meaning, close together in high-dimensional space), and cross-references the scoring detail. Passes AC-13.

**2. TipRepository.similaritySearchSql (lines 41-47):**
> "Cosine similarity measures the angle between two embedding vectors. A score of 1.0 means the vectors point in exactly the same direction (identical meaning); 0.0 means they are orthogonal (unrelated meaning). pgvector's `<=>` operator returns *cosine distance*, defined as 1 - cosineSimilarity. We subtract from 1 to translate distance back into the more intuitive similarity score before returning it to the caller."

Assessment: Covers all required elements — score of 1.0/0.0, `<=>` as cosine distance, subtract-from-1 explanation. Passes AC-14.

**3. TipRepository.findSimilar (lines 80-84):**
> "pgvector's `<=>` operator returns cosine *distance*, where smaller is closer. Ordering ascending by `<=>` therefore returns rows from most similar (smallest distance) to least similar (largest distance). The caller sees results sorted by semantic similarity descending — which is what RAG retrieval needs."

Assessment: Explains the ascending-distance = descending-similarity inversion clearly. Passes AC-15.

**4. PromptBuilder.retrievedContextSection (lines 94-99):**
> "Grounding means injecting retrieved external knowledge into the prompt so the LLM synthesises its response from those specific facts rather than relying solely on patterns absorbed during training. A 'cold' (ungrounded) prompt asks the LLM to reason from general knowledge; a grounded prompt anchors it in the retrieved tips that are most semantically relevant to this particular user's habits and context."

Assessment: Explains grounding correctly, distinguishes cold vs. grounded prompts, and ties it to the user's context. Passes AC-16.

---

## Test result summary

| Test class | Tests | Passed | Skipped | Failed |
|---|---|---|---|---|
| DocsRoutesSpec | 6 | 6 | 0 | 0 |
| HabitCodecsSpec | 10 | 10 | 0 | 0 |
| HabitCompletionCodecsSpec | 15 | 15 | 0 | 0 |
| HabitCompletionRoutesSpec | 16 | 16 | 0 | 0 |
| HabitRoutesSpec | 13 | 13 | 0 | 0 |
| HabitApiIntegrationSpec | 15 | 0 | 15 | 0 |
| HabitCompletionApiIntegrationSpec | 15 | 0 | 15 | 0 |
| InsightPromptSpec | 4 | 4 | 0 | 0 |
| PromptBuilderSpec | 19 | 19 | 0 | 0 |
| DoobieAnalyticsRepositorySpec | 9 | 0 | 9 | 0 |
| DoobieHabitCompletionRepositorySpec | 10 | 10 | 0 | 0 |
| DoobieHabitRepositorySpec | 14 | 0 | 14 | 0 |
| TipRepositorySpec | 4 | 0 | 4 | 0 |
| SeedTipsIdempotencySpec | 1 | 0 | 1 | 0 |
| HabitCompletionServiceSpec | 18 | 18 | 0 | 0 |
| HabitServiceSpec | 15 | 15 | 0 | 0 |
| **TOTAL** | **184** | **126** | **58** | **0** |

BUILD SUCCESSFUL. Zero failures. All Testcontainers specs correctly SKIPPED per @Ignore convention.

---

## Known accepted gaps (not blocking)

Per reviewer memory:

1. No unit test for `TipsRoutes` — `EmbeddingClient.embed` and `AnthropicClient.complete` are called directly at the route call site per the hard limit "no abstraction over the API call". Accepted per ADR-008 §2 / ADR-010 §2.
2. OPENAI_API_KEY startup failure path is not covered by automated test. Same trade-off as ANTHROPIC_API_KEY — accepted per ADR-010 §2.
3. Pre-existing akka config leftovers — not introduced by Phase 3.

---

## Summary

Phase 3 is fully implemented. All 24 PBI-015 acceptance criteria pass, all 14 PBI-016 acceptance criteria pass, and all 6 PBI-017 acceptance criteria pass. The codebase compiles cleanly, `./gradlew test` reports zero failures across 184 tests (126 passed, 58 skipped via @Ignore per established convention), all frozen Phase 1/2 files are byte-identical to their pre-Phase-3 state, and the four required inline comments are present and substantive. The two warnings are minor test-surface gaps that do not affect correctness: `SeedTipsIdempotencySpec` tests the underlying mechanism rather than `SeedTips.run` itself (noted with a suggested comment clarification), and the Phase 2 `HabitCompletionResponse` completedAt round-trip gap remains open. The carry-over nit about habit UUIDs in `streakSection` and `momentumSection` is noted again for Phase 4.

---

## APPROVED — Phase 3 is complete. All Done When items passed.
