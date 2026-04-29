# Phase 2 Retrospective — PBI-014: Habit Analysis Endpoint
# Date: 2026-04-29

---

## 1. What Was Built

Phase 2 delivered the `/users/{userId}/habits/analysis` endpoint, backed by three new SQL
aggregates, a new `PromptBuilder` with six named section methods, and a schema cascade for
time-of-day tracking. All 23 acceptance criteria passed (AC-13 accepted with the same
manual-verification trade-off established in Phase 1).

**New files created:**
- `backend/src/main/scala/com/habittracker/prompt/PromptBuilder.scala`
- `backend/src/main/scala/com/habittracker/http/AnalysisRoutes.scala`
- `backend/src/test/scala/com/habittracker/prompt/PromptBuilderSpec.scala`
- `infra/db/changelog/changesets/005-add-completed-at-to-habit-completions.sql`

**Files modified:**
- `Analytics.scala` — extended HabitContext (7 fields), added AnalysisResponse
- `AnalyticsRepository.scala` — 3 new method signatures
- `DoobieAnalyticsRepository.scala` — 3 SQL implementations + consistency-ranking fix
- `AnalyticsService.scala` — rewrote `buildHabitContext` with parallel execution
- `AnalyticsCodecs.scala` — added AnalysisResponse encoder/decoder
- `AppResources.scala` — wired AnalysisRoutes
- Domain and DTO files for `completedAt: Option[Instant]` cascade (5 files)
- `DoobieAnalyticsRepositorySpec.scala` — 5 new SQL tests, activated @Ignore
- `HabitCompletionCodecsSpec.scala` — 2 new round-trip cases for completedAt
- 4 existing test files — mechanical `completedAt = None` additions

---

## 2. Deviations from Plan

### 2a. HabitContext default values on Phase 2 fields

**What happened:** Three new `HabitContext` fields were given Scala default values
(`= Map.empty`, `= Nil`) to preserve the frozen `InsightPromptSpec`, which constructs
`HabitContext` with only 4 named arguments and cannot be modified.

**Safety assessment (per Reviewer):** Safe. The sole production construction is in
`DefaultAnalyticsService.buildHabitContext`, which always supplies all 7 fields explicitly
by name. The defaults are never invoked by production code. The OpenAPI spec correctly marks
all 7 fields as `required`.

**Pattern established:** When a new field must be added to a shared domain type and a frozen
test constructs that type with fewer arguments, use a default value as a compile-time
compatibility shim — not a runtime fallback. Document explicitly that the default exists only
for test compat and is never triggered in production.

### 2b. Parallel execution pattern

`buildHabitContext` was planned as sequential but implemented with `parTupled` for the four
user-scoped aggregates and `parTraverse` for the per-habit fan-out (streak + momentum). This
is a deliberate improvement, not a deviation from correctness. The pattern is:
- `parTupled` for a fixed set of independent `IO` calls that return different types
- `parTraverse` for a uniform collection of per-element `IO` calls

---

## 3. Mid-Execution Corrections

### 3a. timeOfDaySuccessPattern schema gap

Discovered during BA review that `completedOn: LocalDate` has no hour component, making
time-of-day bucketing impossible. Resolution: Option B — add `completed_at TIMESTAMPTZ NULL`
to `habit_completions` via migration 005. Queries filter `WHERE completed_at IS NOT NULL`
in both numerator and denominator. Users without time-tracked completions see all four
buckets at 0.0 (correct, not misleading).

**This extended the PBI from 16 to 23 ACs** (AC-17 through AC-23 cover the schema cascade).

### 3b. Consistency-ranking denominator fix

Phase 1 used `days_since_habit_created` as the denominator, which is incorrect when
completions are backdated before the habit's `created_at`. Fixed to:
```sql
GREATEST(1, (CURRENT_DATE - COALESCE(MIN(hc.completed_on), CURRENT_DATE))::double precision)
```
The existing Phase 1 test still passes under the new formula (ordering preserved).

### 3c. Type corrections from Phase 2 brief

The brief showed `Map[Long, Int]` and `Map[Long, Double]` for habitId-keyed maps.
Caught during retrospective and corrected in PBI/ADR before implementation.
All code uses `Map[UUID, Int]` and `Map[UUID, Double]` (matching `Habit.id: UUID`).

---

## 4. Review Warnings and Nits

### Warnings (should fix — carried to Phase 3 tech debt):

**W1 [HabitCompletionCodecsSpec.scala:113-151]** — `HabitCompletionResponse` codec tests
never assert a round-trip with `completedAt` populated. All three test cases use
`completedAt = None`. A case with `completedAt = Some(Instant.parse("2026-04-17T09:30:00Z"))`
should be added in a follow-up.

**W2 [DoobieAnalyticsRepositorySpec.scala:214-248]** — `timeOfDaySuccessPattern` test only
seeds "morning" and "afternoon" buckets. "Evening" and "night" are not seeded. AC-11 passes
(4-key assertion only), but coverage is thin. Worth adding a third day with 19:00 UTC in a
follow-up.

### Nits (consider improving — logged for awareness):

**N1 [PromptBuilder.scala:21-24, 82]** — `streakSection` and `momentumSection` render
habit IDs as raw UUIDs rather than names. The prompt would be more useful to the LLM
with human-readable names. Cosmetic for the PoC; worth addressing in Phase 3.

**N2 [DoobieAnalyticsRepositorySpec.scala:164-168]** — Pre-existing `// TODO: test is
incorrect when running on Wednesday` comment. Not introduced by Phase 2. Tracked as
known flaky test in developer MEMORY.

---

## 5. Phase 3 Brief Amendments (applied)

The Phase 3 brief (`docs/phases/phase_3_basic_rag.md`) contained documentation drift
inherited from Phase 2 and earlier briefs. The following corrections were applied:

| Location | Error | Correction |
|----------|-------|------------|
| EXECUTION CONTEXT | `docs/adrs/ADR-001-...` and `ADR-002-...` | `docs/adr/ADR-008-...` and `docs/adr/ADR-009-...` |
| STACK JSON | `io.circe.generic.auto._` | `io.circe.generic.semiauto._` |
| STACK Testing | `munit-cats-effect, CatsEffectSuite` | `ScalaTest AnyWordSpec + @RunWith(classOf[JUnitRunner])` |
| STACK Build | `sbt` | `Gradle (./gradlew)` |
| TipRepository | `class TipRepository[F[_]: Async]` | `class TipRepository(xa: Transactor[IO])` |
| SeedTips run command | `sbt "runMain scripts.SeedTips"` | `./gradlew runSeedTips` (task defined in build.gradle) |
| Architect agent | `Read ADR-001, ADR-002` | `Read ADR-008, ADR-009` |
| Architect output path | `docs/adrs/ADR-003-phase3-basic-rag.md` | `docs/adr/ADR-010-phase3-basic-rag.md` |
| Developer agent | `sbt compile`, `sbt test` | `./gradlew compileScala`, `./gradlew test` |
| Reviewer agent | `sbt test and report pass/fail` | `./gradlew test and report pass/fail` |
| DONE WHEN | `sbt test passes in full` | `./gradlew test passes in full` |

---

## 6. Memory Updates Applied

- **Architect MEMORY** — updated route composition order to include AnalysisRoutes,
  ADR numbering updated (Phase 3 → ADR-010), HabitContext defaults pattern documented.
- **Developer MEMORY** — AppResources route order updated, HabitContext defaults pattern
  added, parallel execution pattern (`parTupled` / `parTraverse`) documented, frozen files
  list extended to include AnalysisRoutes.
- **Reviewer MEMORY** — Phase 3 test coverage expectations added, Phase 2 warnings
  (W1 completedAt round-trip, W2 evening/night bucket coverage) carried forward.

---

## 7. READY FOR PHASE 3

All Phase 2 work is complete. The Phase 3 brief has been corrected. No blocking issues.

Tech debt to address opportunistically (not blocking):
- Add `completedAt`-populated round-trip test to `HabitCompletionCodecsSpec`
- Add evening/night bucket to `timeOfDaySuccessPattern` test
- Use habit names (not UUIDs) in `streakSection` and `momentumSection`
- Fix Wednesday-fragile `completionRateByDayOfWeek` test
