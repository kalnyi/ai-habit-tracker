# Reviewer Agent Memory
# Habit Tracker · Agent Swarm
# Last updated: Phase 4 retrospective (2026-04-30)

---

## Known Non-Blocking Gaps (accepted across ADR-008, 009, 010, 011)

1. No unit test for route handlers that call live API clients (InsightsRoutes, AnalysisRoutes,
   TipsRoutes, NoteRoutes). Live clients called directly per HARD LIMIT. Expected.
2. ANTHROPIC_API_KEY and OPENAI_API_KEY startup failure paths verified manually only.
3. Legacy akka config entries in application.conf and logback.xml. Pre-existing.
4. SeedTipsIdempotencySpec tests TipRepository directly, not SeedTips.run.
5. Eval endpoint deferred — removed in Phase 4 BA phase per engineer decision.

---

## Build Tool Correction

Phase briefs say "sbt test". Actual command: ./gradlew test. Always use ./gradlew.

---

## Review Output Location

Write to: docs/phases/phase_N_review.md  (e.g. phase_5_review.md for Phase 5)

---

## Test Coverage by Phase (cumulative — all must pass in each subsequent phase)

**Phase 1:** InsightPromptSpec (4), DoobieAnalyticsRepositorySpec Phase 1 methods (4 @Ignore)
**Phase 2:** PromptBuilderSpec (19), DoobieAnalyticsRepositorySpec Phase 2 methods (5 @Ignore), HabitCompletionCodecsSpec (15), HabitCompletionRoutesSpec (16)
**Phase 3:** TipRepositorySpec (4 @Ignore), SeedTipsIdempotencySpec (1 @Ignore)
**Phase 4:** DeduplicationSpec (6), RagLoggerSpec (pure), NoteRepositorySpec (4 @Ignore), TipsRoutesParallelRetrievalSpec (pure), NoteRoundtripIntegrationSpec (1 @Ignore)

Total as of Phase 4: 205 tests (142 passed, 63 skipped @Ignore, 0 failed)

---

## HabitContext Field Types

streaks: Map[UUID, Int], momentumScores: Map[UUID, Double] — not Long. Flag as blocking if Long used.
TipsResponse fields: externalTips and personalNotes (not tips — renamed Phase 4).

---

## ADR Location

docs/adr/ (not docs/adrs/). ADR-008 through ADR-011 written. ADR-012 is Phase 5.

---

## HabitContext Defaults and Service Trait Defaults — Do Not Flag

Default values on HabitContext fields and IO.raiseError defaults on service trait methods
are intentional compatibility shims. Do not flag as blocking.

---

## sttp Content-Type Check

For any new sttp HTTP client: Content-Type must be set AFTER .body() with replaceExisting = true.
Setting before .body() is overridden. Phase 3 production bug — OpenAI returned 400.
Flag as blocking in any new client where the target API is strict about Content-Type.

---

## Mandatory Inline Comment Locations (accumulated)

All four Phase 3 locations still apply (embeddings, cosine similarity ×2, grounding).
Phase 4 added:
  5. TipsRoutes.retrieveBoth — explains why parTupled, latency benefit, no extra thread.
     Phase 4 review: EXCEEDS REQUIREMENTS (also covers HikariCP pool safety + ADR ref).

For Phase 5+: any new parallel retrieval, new embedding client, or new RAG pattern
requires a substantive inline comment. "// run in parallel" fails.

---

## Deduplication Checks (Phase 4+)

- Deduplication.scala must have zero F[_], IO, Future — BLOCKING if violated
- Thresholds: SCORE_DIFF_THRESHOLD < 0.05 AND WORD_OVERLAP_THRESHOLD > 0.8
- AND condition boundary: "overlap too low, score diff within threshold → not duplicate"
  is a known untested branch (DeduplicationSpec W2). Note as warning if still missing.

---

## Logging Privacy Check (Phase 4+)

RagLogger must reference only .size, .similarityScore, .headOption — never .content,
.tip.content, or any text field. BLOCKING if user content appears in log statements.

---

## NoteRepository userId Filter (Phase 4+)

NoteRepository.findSimilar must filter by userId. Verify:
1. SQL contains WHERE user_id = $userId
2. NoteRepositorySpec has a two-user test asserting cross-user isolation
Missing either is BLOCKING.

---

## Accumulated Tech Debt (carry forward each phase)

- HabitCompletionCodecsSpec: completedAt round-trip missing (Phase 2)
- HabitCompletionCodecsSpec: BatchCompletionResponse/SkippedCompletion codec tests (Phase 3)
- SeedTipsIdempotencySpec: scope comment missing (Phase 3)
- PromptBuilder: UUID rendering in streakSection/momentumSection (Phase 2)
- NoteRepository.similaritySearchSql: naming shim comment missing (Phase 4)
- DeduplicationSpec: AND boundary test missing (Phase 4)
