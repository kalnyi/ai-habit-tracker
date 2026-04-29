# PBI-014: Phase 2 — Habit Analysis Endpoint with Extended Context and Structured Prompt

## User story

As the habit tracker application, I want a `/analysis` endpoint that runs six SQL
queries to build an extended habit context (three from Phase 1, three new in Phase 2),
assembles a structured multi-section prompt via a pure `PromptBuilder` object, and
calls the Anthropic API to produce a behavioural narrative, so that users receive
deeper, actionable habit analysis beyond the Phase 1 insights view.

---

## Acceptance criteria

- [ ] **AC-1 — GET /users/{userId}/habits/analysis returns HTTP 200.**
  A GET request to `/users/{userId}/habits/analysis` with a valid `userId` (Long)
  returns HTTP 200. The response body is valid JSON.

- [ ] **AC-2 — Response body deserialises to AnalysisResponse.**
  The JSON response deserialises without error to an `AnalysisResponse` case class
  with exactly two fields: `analytics` (type `HabitContext`) and `narrative`
  (type `String`).

- [ ] **AC-3 — analytics contains all six HabitContext fields (three Phase 1 + three Phase 2).**
  The `analytics` field in the response contains all of the following:
  - `userId`: Long
  - `streaks`: Map[UUID, Int] — habit UUID to current streak
  - `completionByDay`: Map[String, Double] — all 7 day names present (Monday–Sunday),
    even where rate is 0.0
  - `consistencyRanking`: List[(String, Double)] sorted descending by score
  - `timeOfDayPatterns`: Map[String, Double] — all 4 keys present: "morning",
    "afternoon", "evening", "night", even where rate is 0.0
  - `correlatedPairs`: List[(String, String, Double)] — top 3 co-occurrence pairs
    sorted by rate descending; empty list if user has fewer than 2 habits
  - `momentumScores`: Map[UUID, Double] — one entry per user habit;
    value in range -1.0 to +1.0; 0.0 when insufficient data (fewer than 7
    completions in either 30-day window)

- [ ] **AC-4 — HabitContext has no removed or renamed fields vs Phase 1.**
  The Phase 1 fields (`userId`, `streaks`, `completionByDay`, `consistencyRanking`)
  are present with identical names and types in the extended `HabitContext`.
  No existing field is removed, renamed, or has its type changed.

- [ ] **AC-5 — narrative is a non-empty string.**
  The `narrative` field in the response is a non-empty string produced by a call to
  the Anthropic API (`claude-sonnet-4-20250514` model). It is not a placeholder or
  hardcoded value.

- [ ] **AC-6 — PromptBuilder.scala exists with 6 named section methods.**
  The file `com/habittracker/prompt/PromptBuilder.scala` exists as a Scala `object`
  and declares exactly these six methods, each accepting `HabitContext` and returning
  `String`, with no `F[_]` or `IO` in any signature:
  `streakSection`, `dayPatternSection`, `rankingSection`, `timeOfDaySection`,
  `correlationSection`, `momentumSection`.

- [ ] **AC-7 — HABIT_COACH_SYSTEM_PROMPT is a named constant in PromptBuilder.**
  `PromptBuilder.HABIT_COACH_SYSTEM_PROMPT` is declared as a `val` (or `final val`)
  directly on the `PromptBuilder` object. It is a non-empty string. It is not
  inlined at the route call site.

- [ ] **AC-8 — PromptBuilder.build is a pure function.**
  `PromptBuilder.build(ctx: HabitContext): String` has no `F[_]` type parameter,
  no `IO`, and no side effects. It calls the 6 section methods in order, filters
  empty strings, and joins non-empty sections with `"\n\n"`.

- [ ] **AC-9 — InsightPrompt.scala is unmodified from Phase 1.**
  The file `com/habittracker/prompt/InsightPrompt.scala` is byte-for-byte identical
  to the version produced in Phase 1. No methods, vals, or imports have been added,
  removed, or altered.

- [ ] **AC-10 — AnthropicClient.scala is unmodified from Phase 1.**
  The file `com/habittracker/client/AnthropicClient.scala` is byte-for-byte identical
  to the version produced in Phase 1. Phase 2 passes different arguments to
  `AnthropicClient.complete` but does not modify the client itself.

- [ ] **AC-11 — All 5 SQL query unit tests pass.**
  The following five test cases exist and pass with `./gradlew test`
  (Testcontainers specs require Docker; they carry `@Ignore` as per Phase 1 convention):
  - `timeOfDaySuccessPattern`: seed completions with explicit `completed_at` timestamps
    spanning known hours; assert bucket rates for all 4 keys ("morning", "afternoon",
    "evening", "night"). Completions with `completed_at IS NULL` are excluded from
    both numerator and denominator.
  - `crossHabitCorrelation`: seed 3 habits with known co-occurrence; assert top pair
  - `crossHabitCorrelation`: user with 1 habit returns empty list
  - `momentumScore`: seed a habit with improving completion over last 30 vs prior 30
    days; assert score is positive (> 0.0)
  - `momentumScore`: fewer than 7 completions in either window; assert score = 0.0

- [ ] **AC-12 — All PromptBuilder unit tests pass.**
  The following pure unit tests exist and pass with `./gradlew test` (no IO, no Docker,
  no `@Ignore`):
  - Each of the 6 section methods: given a fully populated `HabitContext`, returns a
    non-empty `String`
  - `build`: output contains all 6 section contents when `ctx` is fully populated
  - `build`: section methods that receive empty/nil data return empty string, and
    those empty strings are absent from the `build` output
  - `HABIT_COACH_SYSTEM_PROMPT`: is a non-empty string constant on the object

- [ ] **AC-13 — Integration test passes with seeded data.**
  An integration test (or manual verification at the server-level integration spec)
  seeds a test user with at least 2 habits and completions spanning at least 60 days,
  then:
  - Calls `GET /users/{userId}/habits/analysis`
  - Asserts HTTP 200
  - Deserialises the body to `AnalysisResponse`
  - Asserts `analytics` contains all 6 `HabitContext` fields with non-null/non-empty values
  - Asserts `narrative` is a non-empty string

- [ ] **AC-14 — GET /users/{userId}/habits/insights still returns HTTP 200 (Phase 1 regression).**
  The Phase 1 endpoint is unaffected. A GET request to
  `/users/{userId}/habits/insights` with a valid `userId` returns HTTP 200 with a
  valid `InsightResponse` body.

- [ ] **AC-15 — Phase 1 test files pass without modification.**
  `InsightPromptSpec` and `DoobieAnalyticsRepositorySpec` (Phase 1 tests) are not
  modified. `./gradlew test` runs all Phase 1 tests and they report no failures.

- [ ] **AC-16 — ./gradlew test passes in full with zero failures.**
  Running `./gradlew test` from the backend directory produces zero test failures and
  zero compilation errors across all test classes (Phase 1 and Phase 2). Testcontainers
  specs marked `@Ignore` are excluded from this count per the established convention.

- [ ] **AC-17 — Migration 005 adds `completed_at TIMESTAMPTZ NULL` to `habit_completions`.**
  A new Liquibase changeset `infra/db/changelog/changesets/005-add-completed-at-to-habit-completions.sql`
  adds a nullable `completed_at TIMESTAMPTZ` column. Existing rows are unaffected (column is NULL).
  The changeset is registered in `db.changelog-master.xml`.

- [ ] **AC-18 — `HabitCompletion` domain class carries `completedAt: Option[Instant]`.**
  `com.habittracker.domain.HabitCompletion` gains a `completedAt: Option[Instant]` field.
  Existing fields (`id`, `habitId`, `completedOn`, `note`, `createdAt`) are unchanged.

- [ ] **AC-19 — `CreateHabitCompletionRequest` accepts an optional `completedAt`.**
  `com.habittracker.http.dto.CreateHabitCompletionRequest` gains `completedAt: Option[Instant]`.
  The field is optional; clients that omit it record a completion without a timestamp
  (valid for past-date completions where the exact time is unknown).

- [ ] **AC-20 — `HabitCompletionResponse` exposes `completedAt: Option[Instant]`.**
  `com.habittracker.http.dto.HabitCompletionResponse` gains `completedAt: Option[Instant]`
  mapped from `HabitCompletion.completedAt`. All existing fields are unchanged.

- [ ] **AC-21 — `DoobieHabitCompletionRepository` persists and reads `completed_at`.**
  The INSERT includes `completed_at` from `HabitCompletion.completedAt`. All SELECT
  queries include `completed_at` and map it to `HabitCompletion.completedAt`.

- [ ] **AC-22 — All existing habit-completion tests still pass after domain model change.**
  `HabitCompletionRoutesSpec`, `HabitCompletionApiIntegrationSpec`,
  `HabitCompletionServiceSpec`, and `DoobieHabitCompletionRepositorySpec` all pass
  with `./gradlew test` (Docker-dependent specs counted as passing when skipped via `@Ignore`).

- [ ] **AC-23 — `habitConsistencyRanking` uses earliest completion date as denominator.**
  The consistency score formula changes from
  `completions / days_since_habit_created` to
  `completions / GREATEST(1, CURRENT_DATE - MIN(completed_on))`.
  Habits with zero completions score 0.0 (numerator is 0; denominator is 1 by GREATEST guard).
  This applies to both the `/insights` and `/analysis` endpoints since they share the same
  `AnalyticsRepository`.

---

## Out of scope

- **Embeddings, pgvector, or RAG.** No vector store is used. All context comes
  exclusively from SQL queries.
- **Changes to InsightPrompt.scala or AnthropicClient.scala.** These files are
  carry-over contracts from Phase 1 and must not be modified.
- **Changes to the Phase 1 endpoint** (`GET /users/{userId}/habits/insights`) or its
  tests.
- **Frontend changes.** No frontend work in this PBI.
- **Streaming responses.** The Anthropic API is called in standard request/response
  mode only.
- **Authentication or user creation.** `userId` in the path is trusted
  unconditionally. Only the seeded default user is required for tests.
- **Error handling beyond what Phase 1 established.** A failed Anthropic response
  returns an appropriate HTTP 5xx. Detailed error classification is deferred.
- **Phase 3 RAG additions.** Phase 3 adds `retrievedTips: List[String]` to
  `HabitContext` and a `retrievedContextSection` method to `PromptBuilder`. Those
  changes are out of scope here.
- **Cleanup of akka config references in application.conf / logback.xml.** These are
  pre-existing technical debt; this PBI does not address them.

---

## Technical notes for the Architect

### 1. HabitContext type corrections (UUID not Long for habitId keys)

`HabitContext.streaks` is `Map[UUID, Int]` and `HabitContext.momentumScores` is
`Map[UUID, Double]`. The Phase 2 brief shows `Map[Long, Int]` and `Map[Long, Double]`
— those are wrong. `habits.id` is UUID throughout the schema (confirmed in ADR-002,
PBI-012, and Phase 1 implementation). The `momentumScore` SQL query signature is
`momentumScore(userId: Long, habitId: UUID): IO[Double]`. The Architect's ADR-009
must record these corrections explicitly.

### 2. PromptBuilder — 6 named section methods must be pure and independently unit-testable

Every section method (`streakSection`, `dayPatternSection`, `rankingSection`,
`timeOfDaySection`, `correlationSection`, `momentumSection`) must:
- Accept only `HabitContext` and return `String`
- Contain no `F[_]`, no `IO`, and no side effects
- Be callable in isolation in a pure ScalaTest `AnyWordSpec` (no IO runtime required)
- Return an empty string when the relevant portion of `ctx` is empty or nil, so
  `PromptBuilder.build` can filter them with `.filter(_.nonEmpty)`

`PromptBuilder.build` follows the same `List(...).filter(_.nonEmpty).mkString("\n\n")`
pattern established by `InsightPrompt.build` in Phase 1 (which uses a 4-element
`List(header, streakSection, daySection, rankingSection).mkString("\n\n")`).

### 3. Parallel SQL execution in buildHabitContext

`buildHabitContext(userId: Long): IO[HabitContext]` currently runs 3 Phase 1 queries.
Phase 2 adds 3 more. All 6 queries are independent; they must be composed with
`parTupled` or `parTraverse` to execute concurrently against the connection pool.
`momentumScore` is called once per habit; the per-habit calls must use
`parTraverse` over the list of habit IDs. The Architect's ADR-009 must document
the chosen composition strategy and explain why sequential execution is rejected.

### 4. ADR-009 required — scope of decisions to cover

`docs/adr/ADR-009-phase2-habit-analysis.md` must address:

1. **PromptBuilder design** — why it is a standalone `object` in
   `com.habittracker.prompt` (not merged into `InsightPrompt`), why each section
   method is pure, and how the filter-empty pattern prevents blank prompt sections.
2. **Parallel query execution strategy** — which combinator (`parTupled` vs
   `parTraverse`) is used for the 6-query assembly, and for the per-habit
   `momentumScore` fan-out.
3. **AnalysisResponse structure** — why it mirrors `InsightResponse` shape
   (`analytics: HabitContext`, `narrative: String`) rather than introducing a
   separate context type.
4. **Phase 1 endpoint regression verification** — how the Reviewer confirms
   `GET /users/{userId}/habits/insights` still returns HTTP 200 without
   touching Phase 1 source files.
5. **Circe codec placement** — Phase 1 retrospective establishes that
   `AnalysisResponse` encoder/decoder must be added to the existing
   `com.habittracker.http.AnalyticsCodecs` (semiauto derivation). ADR-009 must
   confirm this and note that UUID `KeyEncoder`/`KeyDecoder` are already defined
   there (no redefinition).
6. **Route placement** — whether `/analysis` is added to `InsightsRoutes` or a new
   `AnalysisRoutes` class, and how it is wired in `AppResources.make`.

### 5. Build tool is Gradle

All compile and test commands use `./gradlew compileScala` and `./gradlew test`.
The Phase 2 brief STACK section and Done When checklist both reference `sbt test` —
this is documentation drift identical to the Phase 1 error (recorded in ADR-008
section 7 and Phase 1 retrospective AMEND-1). ADR-009 must repeat this correction
so future agents have it in the ADR chain.

### 6. Stack versions (read-only, no changes permitted)

| Component | Version / detail |
|-----------|-----------------|
| HTTP framework | http4s 0.23.27 + Ember server |
| Effects | Cats Effect 3 — routes and services use `IO`, not `F[_]` |
| Database | Doobie + PostgreSQL (Docker Compose) |
| JSON | Circe via http4s-circe; codecs use `io.circe.generic.semiauto` in `AnalyticsCodecs` |
| HTTP client | sttp with cats-effect backend |
| Testing | ScalaTest `AnyWordSpec` + `@RunWith(classOf[JUnitRunner])` |
| Build | Gradle (`./gradlew`) |
| LLM model | `claude-sonnet-4-20250514` |

Note: the Phase 2 brief STACK section lists munit-cats-effect / CatsEffectSuite.
The Phase 1 retrospective (Section 4, developer memory update) confirms that the
codebase uses ScalaTest `AnyWordSpec` throughout. The Architect must flag this
correction in ADR-009 and the Developer must not introduce munit.

### 7. Files touched by the schema change (not frozen, must be updated)

The following Phase 1 files must be modified to accommodate `completed_at`:

| File | Change |
|------|--------|
| `infra/db/changelog/changesets/005-add-completed-at-to-habit-completions.sql` | New — adds column |
| `infra/db/changelog/db.changelog-master.xml` | Modified — registers changeset 005 |
| `com/habittracker/domain/HabitCompletion.scala` | Modified — add `completedAt: Option[Instant]` |
| `com/habittracker/http/dto/CreateHabitCompletionRequest.scala` | Modified — add `completedAt: Option[Instant]` |
| `com/habittracker/http/dto/HabitCompletionResponse.scala` | Modified — add `completedAt: Option[Instant]` |
| `com/habittracker/repository/DoobieHabitCompletionRepository.scala` | Modified — INSERT + SELECT + Read |
| `com/habittracker/service/HabitCompletionService.scala` | Modified — pass `completedAt` through to domain |
| `com/habittracker/http/CompletionCodecs.scala` | Verify — may need update for new field |
| `com/habittracker/repository/DoobieAnalyticsRepository.scala` | Modified — `habitConsistencyRankingQuery` denominator fix |

These files are NOT frozen carry-over contracts. Only
`AnthropicClient.scala`, `InsightPrompt.scala`, and the `/insights` route handler
are frozen.

### 8. Phase 1 file locations (carry-over contracts, read-only)

All Phase 1 files follow the `com.habittracker.*` package convention (ADR-008 section 1):

| Role | Actual path |
|------|-------------|
| Domain types | `com/habittracker/model/Analytics.scala` |
| Anthropic client | `com/habittracker/client/AnthropicClient.scala` |
| Phase 1 prompt | `com/habittracker/prompt/InsightPrompt.scala` |
| Phase 1 route | `com/habittracker/http/InsightsRoutes.scala` |
| Analytics service | `com/habittracker/service/AnalyticsService.scala` |
| Analytics repository | `com/habittracker/repository/AnalyticsRepository.scala` |
| Codecs | `com/habittracker/http/AnalyticsCodecs.scala` |

Phase 2 adds `com/habittracker/prompt/PromptBuilder.scala`. All other Phase 2
additions extend existing files in the packages above.

### 8. Schema change — `completed_at TIMESTAMPTZ NULL`

The `timeOfDaySuccessPattern` query requires a time-of-day component that does not
exist in the current schema. `habit_completions.completed_on` is `DATE`; there is no
hour information. `created_at` (insertion timestamp) is not a reliable proxy for when
the habit was actually performed.

The resolution: add `completed_at TIMESTAMPTZ NULL` via migration 005. It is nullable
so existing rows and past-date completions without a known time remain valid. The DTO
change is additive: `CreateHabitCompletionRequest` gains an optional `completedAt` field.

The `timeOfDaySuccessPattern` query must filter `WHERE completed_at IS NOT NULL` in both
the numerator and denominator. A user whose completions all have `NULL completed_at` will
see all four time-of-day buckets return 0.0 — this is correct and expected.

The Architect's ADR-009 must document this schema decision and its cascade through the
domain model, repository, DTOs, and codecs. The Developer must not mark the task complete
until `DoobieHabitCompletionRepository` includes `completed_at` in all INSERT and SELECT
statements.

### 9. Consistency ranking denominator fix

The Phase 1 `habitConsistencyRankingQuery` uses `days_since_habit_created` as the
denominator. This is incorrect when completions pre-date the habit's `created_at` (the
API allows recording past-date completions with no lower bound).

New formula: `completions / GREATEST(1, CURRENT_DATE - MIN(completed_on))`.
- `MIN(completed_on)` is the earliest completion date for the habit.
- For habits with zero completions, `MIN` is NULL; `COALESCE(NULL, 0)` → `GREATEST(1, 0) = 1`
  → score = 0 / 1 = 0.0. Correct.
- For habits completed in the future (`completed_on > CURRENT_DATE`), the subtraction
  could be negative. `GREATEST(1, ...)` guards against division by zero or by negative values.

The change is in `DoobieAnalyticsRepository.habitConsistencyRankingQuery`. The Phase 1
test (`habitConsistencyRanking` in `DoobieAnalyticsRepositorySpec`) seeds 5 completions
over 5 days for habit1 and 1 completion today for habit2. The new formula gives habit1
score = 5/4 = 1.25 and habit2 score = 1/1 = 1.0 — habit1 still outranks habit2, so the
existing test assertion passes without modification.

ADR-009 must document this correction and the reasoning.

### 10. LLM use case mapping

This PBI implements the **pattern analysis** LLM use case from CLAUDE.md:
> "weekly summary of habit completion patterns, correlations between habits,
> and risk flags."

The `/analysis` endpoint deepens this use case with three new data dimensions
(time-of-day, cross-habit correlation, momentum trends). `PromptBuilder.HABIT_COACH_SYSTEM_PROMPT`
defines the analysis framing. The Phase 1 `InsightPrompt.SYSTEM_PROMPT` continues to
serve the `/insights` endpoint unchanged.

---

## Dependencies

- PBI-013 (Phase 1) must be complete and all its Done When items passing before
  Phase 2 starts. The Phase 1 retrospective (`docs/phases/phase_1_retrospective.md`)
  confirms readiness as of 2026-04-29.
- ADR-009 must be written and approved by the engineer before the Developer agent
  starts implementation.
- Phase 1 files (`Analytics.scala`, `AnthropicClient.scala`, `InsightPrompt.scala`,
  `AnalyticsRepository`, `AnalyticsCodecs`) must not be modified — they are
  carry-over contracts.

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
XL — expanded from L due to schema migration and cascade through completion domain/DTO/repository
