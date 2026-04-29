# Phase 3 Retrospective — PBI-015/016/017: RAG Tips, Batch Completions, Constraint Verification
# Date: 2026-04-29

---

## 1. What Was Built

Phase 3 delivered three features across one commit:

**PBI-015 — RAG Tips endpoint:**
- `EmbeddingClient` (mirrors AnthropicClient pattern, OpenAI text-embedding-3-small)
- `TipRepository` (hand-rolled pgvector wire format, named `similaritySearchSql` def)
- `TipsRoutes` with TOP_K=3, full 6-step RAG for-comprehension
- `SeedTips` IOApp with idempotent SELECT-then-INSERT, `runSeedTips` Gradle task
- `PromptBuilder` extended with `retrievedContextSection` and widened `build` signature
- `habit_tips.txt` corpus (26 tips, 10 topic areas)
- Docker init scripts (`01_enable_vector.sql`, `02_create_habit_tips.sql`)
- All 4 required inline comments present and substantive (confirmed by Reviewer)

**PBI-016 — Batch completions endpoint:**
- `BatchCompletionRoutes` with unconditional HTTP 200
- `BatchCompletionItem`, `BatchCompletionResponse`, `SkippedCompletion` DTOs
- `HabitCompletionService.recordCompletionBatch` with sequential `traverse` + `.fold` pattern
- Partial success: duplicates and not-found items route to `skipped`, batch continues

**PBI-017 — Unique constraint verification:**
- Confirmed `uq_habit_completions_habit_day` exists in migration 002
- 409 on single endpoint confirmed, duplicate→skipped on batch confirmed
- No new migration needed

**Test count: 184 total (126 passed, 58 skipped via @Ignore, 0 failed)**

---

## 2. Deviations from Plan

### 2a. `recordCompletionBatch` concrete on the trait

**What happened:** Plan showed the method as abstract on `HabitCompletionService` trait. The frozen
`FakeHabitCompletionService` in `HabitCompletionRoutesSpec` extends the trait and cannot be modified.
Adding an abstract method would break it.

**Resolution:** Method given a concrete default of `IO.raiseError(new NotImplementedError(...))` on
the trait. `DefaultHabitCompletionService` overrides it. Reviewer confirmed this is safe.

**Pattern established:** When adding a new method to a service trait where a frozen test fake exists,
use a concrete `IO.raiseError(NotImplementedError)` default on the trait — do not make it abstract.

### 2b. `SeedTipsIdempotencySpec` exercises `TipRepository` directly, not `SeedTips.run`

**What happened:** `SeedTips.run` calls `EmbeddingClient.embed` (live OpenAI), making it impossible
to test without a real API key. The spec was written to test the idempotency mechanism
(SELECT-then-INSERT guard) using dummy embeddings directly against `TipRepository`.

**Trade-off:** Any bug in `SeedTips.run` itself (IOApp wiring, logging call, resource bracket)
would not be caught. Reviewer flagged this as a warning with a suggestion to rename the test or
add a scope comment. Accepted under the established live-API `@Ignore` trade-off.

### 2c. `BatchCompletionRoutes` needed `HabitCodecs._` import

Minor: needed for `ErrorResponse` encoding on 400 responses. Consistent with how other routes
handle this. Not a plan deviation in substance.

---

## 3. Post-Review Bug Fix

**EmbeddingClient content-type bug (discovered during manual testing):**

`./gradlew runSeedTips` returned HTTP 400 from OpenAI: "you must provide a model parameter".
The JSON body was correctly constructed with `model` and `input` fields, but OpenAI wasn't
parsing it as JSON.

**Root cause:** In sttp v3, calling `.body(string)` sets the body AND adds
`Content-Type: text/plain; charset=utf-8`. The preceding
`.header("content-type", "application/json")` was then overridden because sttp's
default `replaceExisting = false` does not protect earlier headers against body-setter overrides.
OpenAI strictly requires `Content-Type: application/json` and rejected the request.

**Why AnthropicClient wasn't affected:** Anthropic is lenient about content-type and parses the
body as JSON regardless. The same bug exists in `AnthropicClient` but is harmless there.
`AnthropicClient` is frozen so the bug remains — it doesn't affect correctness in practice.

**Fix applied:** In `EmbeddingClient.scala`:
```scala
// Before (buggy):
.header("content-type", "application/json")
.body(bodyJson)

// After (fixed):
.body(bodyJson)
.header("Content-Type", "application/json", replaceExisting = true)
```

**Rule for all future sttp callers:** Always set `Content-Type` AFTER `.body()` with
`replaceExisting = true`. Never set it before `.body()`.

---

## 4. Review Warnings and Nits

### Warnings (carried to Phase 4 tech debt):

**W1 [SeedTipsIdempotencySpec]** — Test title says "running twice produces exactly as many rows"
but `SeedTips.run` is never called. Reviewer suggests renaming to "TipRepository insert-then-skip
is idempotent" or adding a `// NOTE: SeedTips.run is not called here` comment.

**W2 [HabitCompletionCodecsSpec]** — `HabitCompletionResponse` round-trip with populated
`completedAt` still missing. Carried from Phase 2. Phase 3 added `completedAt` tests for
`CreateHabitCompletionRequest` (request side) but not the response side.

**W3 [HabitCompletionCodecsSpec]** — `BatchCompletionResponse` and `SkippedCompletion` codecs
have no unit-level round-trip tests. Only covered through `@Ignore` integration spec.

### Nits:

**N1 [BatchCompletionRoutes]** — `handleErrorWith { case _: DecodeFailure => ... }` is a partial
function. Pre-existing convention from `HabitCompletionRoutes`. Not a new defect.

**N2 [AnalyticsService.buildTipsQuery]** — Fallback text "General habit-building practical advice."
fires when `consistencyRanking` is empty OR `worstDays.size < 2`. Cosmetic for PoC.

**N3 [PromptBuilder]** — Carry-over from Phase 2: `streakSection` and `momentumSection` render
habit IDs as raw UUIDs instead of names.

---

## 5. Phase 4 Brief Amendments (applied)

The Phase 4 brief (`docs/phases/phase_4_full_rag.md`) contained the same documentation drift
as earlier briefs. The following corrections were applied:

| Location | Error | Correction |
|----------|-------|------------|
| EXECUTION CONTEXT | `docs/adrs/ADR-001-...`, `ADR-002-...`, `ADR-003-...` | `docs/adr/ADR-008-...`, `docs/adr/ADR-009-...`, `docs/adr/ADR-010-...` |
| STACK JSON | `io.circe.generic.auto._` | `io.circe.generic.semiauto._` |
| STACK Testing | `munit-cats-effect, CatsEffectSuite` | `ScalaTest AnyWordSpec + @RunWith(classOf[JUnitRunner])` |
| STACK Build | `sbt` | `Gradle (./gradlew)` |
| NoteRepository | `class NoteRepository[F[_]: Async]` | `class NoteRepository(xa: Transactor[IO])` |
| Architect output path | `docs/adrs/ADR-004-phase4-full-rag.md` | `docs/adr/ADR-011-phase4-full-rag.md` |
| Developer agent | `sbt compile`, `sbt test` | `./gradlew compileScala`, `./gradlew test` |
| Reviewer agent | `sbt test and report pass/fail` | `./gradlew test and report pass/fail` |
| Done When | `sbt test passes in full` | `./gradlew test passes in full` |

---

## 6. Memory Updates Applied

- **Architect MEMORY** — ADR-011 for Phase 4, TipsRoutes NOT frozen in Phase 4 (modified for
  parallel retrieval), NoteRepository uses IO not F[_], Phase 4 ADR path.
- **Developer MEMORY** — sttp content-type rule (set AFTER .body() with replaceExisting=true),
  service trait new-method pattern (concrete default not abstract), TipsRoutes modified in Phase 4.
- **Reviewer MEMORY** — Phase 4 test coverage expectations, content-type check for new HTTP
  clients, SeedTipsIdempotencySpec scope caveat.

---

## 7. READY FOR PHASE 4

All Phase 3 work is complete (including post-review EmbeddingClient fix). Phase 4 brief corrected.

Tech debt to address opportunistically:
- Rename or add scope comment to `SeedTipsIdempotencySpec`
- Add `HabitCompletionResponse` completedAt round-trip test (Phase 2 carry-over)
- Add `BatchCompletionResponse` / `SkippedCompletion` codec unit tests
- Fix UUID rendering in `streakSection` / `momentumSection` (Phase 2 carry-over)
