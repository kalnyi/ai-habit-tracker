# Reviewer Agent Memory
# Habit Tracker · Agent Swarm
# Last updated: Phase 3 retrospective (2026-04-29)

---

## Known Non-Blocking Gaps (accepted by ADR-008, ADR-009, ADR-010)

These are accepted trade-offs, not defects. Report as "known accepted gaps", not blocking:

1. No unit test for InsightsRoutes, AnalysisRoutes, TipsRoutes, NoteRoutes (Phase 4).
   Live API clients called directly at the route call site per HARD LIMIT.

2. ANTHROPIC_API_KEY and OPENAI_API_KEY startup failure paths not covered by automated test.
   Verified manually only. Same pattern for any future API key.

3. grep -r "akka" src/ returns 5 results from application.conf and logback.xml.
   Config leftovers from the http4s migration. No Scala akka imports. Pre-existing.

4. SeedTipsIdempotencySpec tests TipRepository directly, not SeedTips.run.
   Accepted under the live-API @Ignore trade-off. Note this in review; not blocking.

---

## Build Tool Correction

Phase briefs reference "sbt test" in Done When checklists.
Actual command is: ./gradlew test
Run ./gradlew test and report pass/fail counts from that output.

---

## Review Output Location

Write review output to: docs/phases/phase_N_review.md
(e.g. docs/phases/phase_4_review.md for Phase 4)

---

## Test Coverage Expectations by Phase

Phase 1 test surface (must remain passing):
  - InsightPromptSpec: 4 pure unit tests
  - DoobieAnalyticsRepositorySpec: 4 Testcontainers tests (@Ignore)

Phase 2 test surface (must remain passing):
  - PromptBuilderSpec: 19 pure unit tests (includes Phase 3 additions)
  - DoobieAnalyticsRepositorySpec: 5 new SQL tests (@Ignore)
  - HabitCompletionCodecsSpec: 15 tests
  - HabitCompletionRoutesSpec: 16 tests

Phase 3 test surface (must remain passing):
  - TipRepositorySpec: 4 tests (@Ignore, pgvector/pgvector:pg17 image)
  - SeedTipsIdempotencySpec: 1 test (@Ignore)
  - PromptBuilder tests (Phase 3 additions): 4 tests
  - TipsResponse tests: UPDATE EXPECTED for Phase 4 field rename (externalTips/personalNotes)

Phase 4 test surface (to verify when reviewing Phase 4):
  - NoteRepositorySpec: 4 Testcontainers tests (insert, findSimilar topK, userId filtering, empty)
  - DeduplicationSpec: 6 pure unit tests
  - New PromptBuilder tests: personalNotesSection (×2), build with both sources (×2)
  - Parallel retrieval test: verifies both findSimilar calls are made
  - Integration tests: POST /notes + GET /tips, evaluate with match, evaluate without match
  - Phase 1, 2, 3 regression: all must pass (TipsResponse tests updated for rename)

---

## HabitContext Field Types (brief has errors)

The phase briefs have incorrect types for habitId-keyed maps.
When reviewing code for any phase, the correct types are:
  streaks:        Map[UUID, Int]    (not Map[Long, Int])
  momentumScores: Map[UUID, Double] (not Map[Long, Double])
Flag as blocking if the developer used Long.

---

## ADR Location

ADRs live at docs/adr/ (not docs/adrs/ — index.md has this wrong).
  Phase 1: docs/adr/ADR-008-phase1-pattern-detection.md
  Phase 2: docs/adr/ADR-009-phase2-habit-analysis.md
  Phase 3: docs/adr/ADR-010-phase3-basic-rag.md
  Phase 4: docs/adr/ADR-011-phase4-full-rag.md
If the Architect wrote to the wrong path, flag as blocking.

---

## HabitContext Defaults Pattern (safe — do not flag)

New HabitContext fields with default values (= Map.empty, = Nil) are a safe
compile-time shim for frozen tests. Production code always supplies all fields.
Do NOT flag these defaults as blocking.

---

## Service Trait Concrete Default Pattern (safe — do not flag)

New service trait methods with `IO.raiseError(NotImplementedError)` defaults are
intentional — prevents breaking frozen test fakes. Do NOT flag as blocking.

---

## sttp Content-Type Check (Phase 4 and beyond)

For any new sttp HTTP client (e.g., calls to external APIs):
Verify that Content-Type is set AFTER .body() with replaceExisting = true:
  .body(bodyJson)
  .header("Content-Type", "application/json", replaceExisting = true)

Setting it BEFORE .body() is a bug — sttp's string body setter overrides earlier headers.
This caused a production failure in Phase 3 (EmbeddingClient returned HTTP 400 from OpenAI).
AnthropicClient has the same bug but is harmless (Anthropic is lenient). Flag as blocking
in any new client where the API is strict about Content-Type.

---

## Inline Comments — Mandatory Locations

Phase 3 established 4 mandatory inline comment locations (all pass as of Phase 3 review).
Phase 4 adds a 5th:
  5. TipsRoutes (or wherever retrieveBoth is defined) — explains why parTupled is used
     for parallel retrieval vs sequential. Reviewer will read the actual comment.
     A content-free comment ("// run both in parallel") fails — must explain latency
     benefit and Cats IO parTupled mechanics.

---

## Phase 3 Tech Debt (carry to Phase 4 if not resolved)

- HabitCompletionCodecsSpec: missing HabitCompletionResponse completedAt round-trip
- HabitCompletionCodecsSpec: missing BatchCompletionResponse/SkippedCompletion codec unit tests
- SeedTipsIdempotencySpec: add // NOTE: SeedTips.run is not called here comment
- PromptBuilder: UUID rendering in streakSection/momentumSection instead of habit names

---

## Phase 4 Special Checks

**Deduplication purity:** `Deduplication.scala` must contain no F[_], no IO, no Future.
Any effect type in Deduplication is a blocking issue.

**Logging content:** `RagLogger` must log scores and counts only — never tip content,
note content, or user text of any kind. Read the actual log statements.

**NoteRepository userId filtering:** Verify a dedicated test confirms findSimilar does
not return other users' notes. Missing userId filter is a blocking issue.

**parTupled usage:** Parallel retrieval must use `parTupled` from cats.syntax.parallel.
Using Future, Thread, or any non-IO concurrency primitive is a blocking issue.
