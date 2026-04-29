# Reviewer Agent Memory
# Habit Tracker · Agent Swarm
# Last updated: Phase 2 retrospective (2026-04-29)

---

## Known Non-Blocking Gaps (accepted by ADR-008 and ADR-009)

These are accepted trade-offs, not defects. Report them as "known accepted gaps"
in the review, not as blocking issues:

1. No unit test for InsightsRoutes (Phase 1), AnalysisRoutes (Phase 2), or
   TipsRoutes (Phase 3). AnthropicClient.complete is called directly at the route
   call site per HARD LIMIT "no abstraction over the API call". The route is
   untestable without a live API key.

2. ANTHROPIC_API_KEY startup failure path is not covered by automated test.
   Verified manually by engineer: unset var → ./gradlew run → observe error.
   Scala object-init cannot be tested in the same JVM without a child process.
   Same applies to OPENAI_API_KEY (Phase 3).

3. grep -r "akka" src/ returns 5 results from application.conf and logback.xml.
   These are config leftovers from the http4s migration (commit e558cd3, before Phase 1).
   No Scala akka imports exist. Not a failure — note as pre-existing tech debt.

---

## Build Tool Correction

Phase briefs reference "sbt test" in Done When checklists.
Actual command is: ./gradlew test
Run ./gradlew test and report pass/fail counts from that output.

---

## Review Output Location

Write review output to: docs/phases/phase_N_review.md
(e.g. docs/phases/phase_3_review.md for Phase 3)

This file is required by the retrospective process. If it is not written,
the next retrospective cannot confirm Done When items passed.

---

## Test Coverage Expectations by Phase

Phase 1 test surface:
  - InsightPromptSpec: 4 pure unit tests (should always pass in CI)
  - DoobieAnalyticsRepositorySpec: 4 Testcontainers tests (@Ignore — manual Docker only)
  - All pre-Phase-1 tests: must remain passing

Phase 2 test surface (verified in Phase 2 review — must remain passing in Phase 3):
  - PromptBuilderSpec: 16 pure unit tests (6 section non-empty + 6 section empty-data +
    build full + build filter-empty + constant)
  - DoobieAnalyticsRepositorySpec: 5 new SQL tests (timeOfDaySuccessPattern ×1,
    crossHabitCorrelation ×2, momentumScore ×2) — all @Ignore, Docker only
  - HabitCompletionCodecsSpec: 13 tests including completedAt round-trips
  - HabitCompletionRoutesSpec: 13 tests (completedAt = None on existing cases)
  - Phase 1 regression: GET /users/{userId}/habits/insights must still work

Phase 3 test surface (to verify when reviewing Phase 3):
  - TipRepository tests: insert, findSimilar (topK results), findSimilar (ordered),
    findSimilar (empty table) — all Testcontainers, @Ignore
  - New PromptBuilder tests: retrievedContextSection non-empty, retrievedContextSection
    empty, build with tips, build without tips (Phase 2 regression)
  - SeedTips idempotency test against test database
  - Phase 1 and Phase 2 regression: both endpoints still return 200
  - Integration test for GET /users/{userId}/habits/tips: accepted as manual-only
    per the established trade-off (AnthropicClient + EmbeddingClient not stubbable)

---

## HabitContext Field Types (brief has errors)

The phase briefs have incorrect types for habitId-keyed maps.
When reviewing code for any phase, the correct types are:
  streaks:        Map[UUID, Int]    (not Map[Long, Int])
  momentumScores: Map[UUID, Double] (not Map[Long, Double])
If the developer used Long for these maps, flag as blocking — the types do not
match HabitRepository.listActive which returns List[Habit] with id: UUID.

---

## ADR Location

ADRs live at docs/adr/ (not docs/adrs/ — index.md has this wrong).
  Phase 1: docs/adr/ADR-008-phase1-pattern-detection.md
  Phase 2: docs/adr/ADR-009-phase2-habit-analysis.md
  Phase 3: docs/adr/ADR-010-phase3-basic-rag.md
If the Architect wrote to docs/adrs/ or used wrong numbers, flag as blocking.

---

## HabitContext Defaults Pattern (Phase 2 established)

Phase 2 added default values to the three new HabitContext fields to preserve
the frozen InsightPromptSpec. This is accepted and safe — production code always
supplies all fields explicitly. Do NOT flag these defaults as blocking issues.
Phase 3 adds retrievedTips: List[String] = Nil using the same pattern — also safe.

---

## Inline Comments Are Mandatory in Phase 3

Phase 3 introduces four required inline comment locations. Missing or content-free
comments (e.g. restating the code) are a BLOCKING issue:
  1. EmbeddingClient — explains what an embedding vector is in plain terms
  2. TipRepository.similaritySearchSql — explains cosine similarity in plain terms
  3. TipRepository.findSimilar — explains why ORDER BY distance gives semantic results
  4. PromptBuilder.retrievedContextSection — explains what "grounding" means in RAG

Read the actual comments. A comment like "// embeds the text" fails — it must explain
the concept to a reader with no prior knowledge of embeddings or RAG.

---

## Phase 2 Tech Debt (carry to Phase 3 if not resolved)

These are warnings from the Phase 2 review — not blocking, but worth noting:
- HabitCompletionCodecsSpec: missing completedAt-populated round-trip test
- DoobieAnalyticsRepositorySpec: timeOfDaySuccessPattern has no evening/night seed
- PromptBuilder: habit UUIDs used instead of names in streakSection/momentumSection
