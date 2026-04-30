# Phase 4 Retrospective — PBI-018: Full RAG Pipeline
# Date: 2026-04-30

---

## 1. What Was Built

Phase 4 completed the RAG pipeline with multi-source retrieval, deduplication, and observability.

**New files created (11):**
- `infra/db/init/03_create_user_notes.sql`
- `backend/src/main/scala/com/habittracker/repository/NoteRepository.scala`
- `backend/src/main/scala/com/habittracker/service/Deduplication.scala`
- `backend/src/main/scala/com/habittracker/observability/RagLogger.scala`
- `backend/src/main/scala/com/habittracker/http/NoteRoutes.scala`
- `docs/future_improvements.md`
- `DeduplicationSpec`, `RagLoggerSpec`, `NoteRepositorySpec`, `TipsRoutesParallelRetrievalSpec`, `NoteRoundtripIntegrationSpec`

**Modified files (7):** Analytics.scala, AnalyticsCodecs.scala, PromptBuilder.scala, TipsRoutes.scala,
AppResources.scala, openapi.yaml, PromptBuilderSpec.scala

**Test count: 205 total (142 passed, 63 skipped @Ignore, 0 failed)**

Key design outcomes:
- Parallel retrieval via `parTupled` — both sources retrieved concurrently, latency halved
- Pure deduplication (no F[_]) using 0.05 score diff + 0.8 word overlap thresholds
- RagLogger logs scores/counts only — no user content in logs (privacy contract enforced)
- TipsResponse breaking change landed cleanly — zero test references to old `tips` field found
- Eval endpoint removed before implementation (decision made during BA phase)

---

## 2. Deviations from Plan

### 2a. Deduplication test score data corrected

**What happened:** The plan's example test data used scores 0.90 and 0.85 (diff = exactly 0.05).
The deduplication algorithm uses strict `< 0.05`, so this pair would NOT deduplicate — the test
would have failed. Developer corrected to 0.90/0.88 (diff = 0.02) and 0.90/0.93.

**Algorithm unchanged.** Only test fixture data was adjusted.

### 2b. RagLogger test capture required custom IORuntime

**What happened:** `Console.withOut` alone failed to capture `IO.delay(println(...))` output
across cats-effect's work-stealing thread pool — the capturing happened on a different thread
than the IO executor. Fix: fresh single-threaded `IORuntime` per capture call combined with
`System.setOut`.

**Production code unchanged.** Test infrastructure adaptation only.

### 2c. AC-28 satisfied vacuously

Phase 4 introduces no new sttp HTTP calls — `NoteRoutes` delegates to the frozen
`EmbeddingClient`. The sttp Content-Type rule (set after .body() with replaceExisting=true)
was not exercised because no new HTTP clients were added.

---

## 3. Scope Change Mid-BA Phase

**Eval endpoint removed** (POST /users/{userId}/habits/tips/evaluate, EvalRequest, EvalResponse).

**Why:** The brief's design had `question` field unused in the pipeline (the endpoint re-ran
`buildTipsQuery` regardless of the question), making the eval result independent of what was
asked. After discussion the engineer concluded the endpoint had no meaningful application value
in this PoC and was only useful as a debugging tool — better addressed through offline evaluation
with persisted results in a future phase.

**Recorded in:** ADR-011 §7, `docs/future_improvements.md` (EVAL PERSISTENCE section).

---

## 4. Review Findings

**Inline comment on `retrieveBoth`:** Reviewer assessed as EXCEEDS REQUIREMENTS — covers the
three mandatory points plus HikariCP pool safety with ADR cross-reference.

### Warnings (carried to Phase 5 tech debt):

**W1 [NoteRepository.scala]** — `val similaritySearchSql` is a named string with `?` placeholders
but no comment explaining it's a naming shim for the AC requirement (same pattern as TipRepository,
but TipRepository also lacks this comment). Low-friction fix: add a one-line comment.

**W2 [DeduplicationSpec.scala]** — Missing boundary test for the AND condition: "score diff within
threshold but word overlap too low → not a duplicate". The 6 existing tests satisfy AC-10 but
the AND condition is not independently exercised. The algorithm could regress on this branch
without the test suite catching it.

---

## 5. Parallel Memory System Discovered

The Architect agent (and other agents) created a second memory system under
`.claude/agent-memory/` containing 18 files across ba-agent, architect-agent, developer-agent,
and reviewer-agent subdirectories. This is separate from the `.agent/*/MEMORY.md` files
maintained since Phase 1.

**Current state:** Two parallel memory systems exist with overlapping but inconsistent content.
**Recommendation:** Consolidate into one location before Phase 5. The user mentioned wanting to
think over memory file organization — this is now more urgent given the divergence.

The `.agent/*/MEMORY.md` files are the authoritative source as they have been maintained
consistently through all retrospectives. The `.claude/agent-memory/` files may contain
supplementary detail worth merging.

---

## 6. Memory Updates Applied

- **Architect MEMORY** — Phase 5 ADR would be ADR-012, new packages established
  (com.habittracker.service.Deduplication, com.habittracker.observability), NoteRepository
  pattern documented, eval endpoint removal recorded.
- **Developer MEMORY** — RagLogger IORuntime test pattern, deduplication threshold values,
  TipsRoutes constructor now takes 3 args, TipsResponse field names updated.
- **Reviewer MEMORY** — Phase 5 test surface TBD (no brief yet), W2 boundary test gap
  carried forward, deduplication AND condition as known untested branch.

---

## 7. READY FOR PHASE 5

All Phase 4 work complete. No Phase 5 brief exists yet.

Tech debt to address opportunistically:
- Add `// naming shim` comment to NoteRepository.similaritySearchSql
- Add DeduplicationSpec boundary test for AND condition (overlap too low)
- Consolidate `.agent/*/MEMORY.md` and `.claude/agent-memory/` into one location
- HabitCompletionCodecsSpec: completedAt round-trip test (Phase 2 carry-over)
- HabitCompletionCodecsSpec: BatchCompletionResponse/SkippedCompletion codec unit tests (Phase 3)
- SeedTipsIdempotencySpec: add scope comment (Phase 3)
- PromptBuilder: UUID rendering in streakSection/momentumSection (Phase 2)
