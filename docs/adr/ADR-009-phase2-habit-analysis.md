# ADR-009: Phase 2 habit analysis — `completed_at` schema cascade, consistency-ranking denominator fix, parallel context assembly, PromptBuilder placement, route placement, codec placement

## Status
Accepted

## Context

PBI-014 (Phase 2 — Habit Analysis Endpoint with Extended Context and Structured
Prompt) extends Phase 1 with three new analytical SQL queries
(`timeOfDaySuccessPattern`, `crossHabitCorrelation`, `momentumScore`), an
extended `HabitContext` shape, a new pure `PromptBuilder` object that assembles
a six-section prompt, a new endpoint `GET /users/{userId}/habits/analysis`, and a
schema change that introduces `completed_at TIMESTAMPTZ NULL` on
`habit_completions`. It also fixes a Phase 1 bug: the consistency-ranking
denominator must use the earliest completion date, not the habit's `created_at`,
because past-date completions are allowed.

The PBI's Technical Notes section lists ten architectural questions that need an
explicit, written resolution before the Developer agent can start. They span
two work streams:

- **Stream A (ACs 17–22) — schema cascade.** The new column needs a Liquibase
  changeset, a domain-model field, two DTO updates, the Doobie repository
  Read/INSERT/SELECT, and a service pass-through.
- **Stream B (ACs 1–16, 23) — analytics + prompt + endpoint.** The
  `HabitContext` gains three fields (typed `Map[UUID, ...]` not `Map[Long, ...]`),
  three SQL queries are added, six independent queries are composed in parallel,
  the `PromptBuilder` is its own pure object alongside (not replacing)
  `InsightPrompt`, and a new route is wired without touching the Phase 1
  `/insights` route.

The HARD LIMITS block forbids any modification to
`AnthropicClient.scala`, `InsightPrompt.scala`, the existing `/insights` route,
or any Phase 1 test file. Existing `HabitContext` fields must not be removed,
renamed, or retyped (AC-4).

ADR-008 already locked six Phase 1 decisions that this ADR builds on: file
layout under `com.habittracker.{model,client,prompt}`; `AnthropicClient` is a
no-trait object called directly; analytical SQL lives in
`AnalyticsRepository`; SQL tests use Testcontainers; and Gradle is the build
tool. ADR-009 does not contradict any of these — it extends them.

The Phase 1 retrospective recorded three correctness items that ADR-009 must
also re-iterate so future agents have a single source of truth in the ADR
chain: (a) `HabitContext` `habitId` keys are `UUID` not `Long`; (b) the
codebase uses ScalaTest `AnyWordSpec` + `@RunWith(classOf[JUnitRunner])`, not
munit; (c) circe codecs use `io.circe.generic.semiauto` in `AnalyticsCodecs`,
not `io.circe.generic.auto`.

---

## Decision

### 1. `completed_at TIMESTAMPTZ NULL` — nullable column with documented NULL semantics in `timeOfDaySuccessPattern`

A new Liquibase changeset
`infra/db/changelog/changesets/005-add-completed-at-to-habit-completions.sql`
adds the column:

```sql
--liquibase formatted sql
--changeset habit-tracker:005-add-completed-at-to-habit-completions
--comment: Adds optional completed_at TIMESTAMPTZ for time-of-day analysis. See ADR-009.

ALTER TABLE habit_completions
    ADD COLUMN completed_at TIMESTAMPTZ NULL;

--rollback ALTER TABLE habit_completions DROP COLUMN IF EXISTS completed_at;
```

The changeset is registered in
`infra/db/changelog/db.changelog-master.xml` between the existing 004 entry
and the close of `<databaseChangeLog>`.

**Why nullable, not NOT NULL with a default:**

- `completed_on DATE` (the existing column) is the *what-day* fact; it remains
  NOT NULL and is unchanged. `completed_at` is a *what-clock-time* fact,
  optional by design.
- The API allows recording past-date completions where the user did not record
  a precise hour. A `NOT NULL` column would force the application to pick a
  fictional time (e.g. midnight) which would skew `timeOfDaySuccessPattern` by
  inflating the "night" bucket for every retroactive completion.
- A `DEFAULT NOW()` would attach the *insertion* timestamp to a *retroactive*
  completion — semantically wrong, and we already have `created_at` for that.
- Existing rows (created before migration 005) have no time-of-day signal; they
  must be excluded from the time-of-day analysis. NULL is the natural,
  three-valued-logic fit.

**NULL handling in `timeOfDaySuccessPattern`:** the SQL filters
`WHERE hc.completed_at IS NOT NULL` in *both* the numerator and the denominator.
A user whose completions are all NULL-`completed_at` returns all four buckets
at 0.0. The four bucket keys (`"morning"`, `"afternoon"`, `"evening"`,
`"night"`) are always present in the returned `Map[String, Double]`, populated
client-side in `DoobieAnalyticsRepository.timeOfDaySuccessPattern` with
`getOrElse(bucket, 0.0)` against the four-element ordered list — same pattern
already used by `completionRateByDayOfWeek`.

The unit test for `timeOfDaySuccessPattern` (PBI AC-11) seeds completions with
explicit `completed_at` timestamps spanning known hours and asserts bucket rates;
a separate assertion confirms that completions with `completed_at IS NULL` are
excluded from both numerator and denominator.

### 2. `HabitCompletion` domain evolution — additive, append the new field

The `HabitCompletion` case class adds `completedAt: Option[Instant]`
**at the end** of the parameter list:

```scala
final case class HabitCompletion(
    id: UUID,
    habitId: UUID,
    completedOn: LocalDate,
    note: Option[String],
    createdAt: Instant,
    completedAt: Option[Instant]   // new in Phase 2
)
```

Rationale for the position: Scala 2.13 case classes use positional arity in
`copy`, `Read`/`Write` Doobie instances, and `unapply` extractors. Appending
keeps every existing positional construction working until the file that
constructs the instance is updated; the compiler will list call sites that
need the new positional or named argument. (Inserting in the middle would shift
positional indices and break call sites silently for pattern-match `copy(...)`
calls — there are none in this codebase, but the rule "append fields"
preempts surprise in future PBIs.)

The `Read[HabitCompletion]` instance in `DoobieHabitCompletionRepository`
extends the implicit Read with the new column. The new tuple shape is
`(UUID, UUID, LocalDate, Option[String], Instant, Option[Instant])`:

```scala
private implicit val completionRead: Read[HabitCompletion] =
  Read[(UUID, UUID, LocalDate, Option[String], Instant, Option[Instant])].map {
    case (id, habitId, completedOn, note, createdAt, completedAt) =>
      HabitCompletion(id, habitId, completedOn, note, createdAt, completedAt)
  }
```

`Read[Option[Instant]]` is supplied by `doobie.postgres.implicits._` (already
imported in the file) which provides `Meta[Instant]` for `TIMESTAMPTZ`; Doobie
lifts that to `Read[Option[Instant]]` automatically because the column is
nullable.

### 3. `habitConsistencyRanking` denominator fix — `MIN(completed_on)` not `created_at`

**Before (Phase 1, bug):**
```sql
SELECT
  h.name,
  (COALESCE(COUNT(hc.id), 0)::double precision
    / GREATEST(
        1,
        EXTRACT(EPOCH FROM (NOW() - h.created_at)) / 86400.0
      )
  )::double precision AS score
FROM habits h
LEFT JOIN habit_completions hc ON hc.habit_id = h.id
WHERE h.user_id = $userId AND h.deleted_at IS NULL
GROUP BY h.id, h.name, h.created_at
ORDER BY score DESC, h.name ASC
```

**After (Phase 2, correct):**
```sql
SELECT
  h.name,
  (COALESCE(COUNT(hc.id), 0)::double precision
    / GREATEST(
        1,
        (CURRENT_DATE - COALESCE(MIN(hc.completed_on), CURRENT_DATE))::double precision
      )
  )::double precision AS score
FROM habits h
LEFT JOIN habit_completions hc ON hc.habit_id = h.id
WHERE h.user_id = $userId AND h.deleted_at IS NULL
GROUP BY h.id, h.name
ORDER BY score DESC, h.name ASC
```

**Why this is the correct denominator:**

- The API permits past-date completions: a user can record a completion for
  `completed_on = '2025-01-01'` even though the habit was created in April 2026.
  Phase 1's denominator (`NOW() - h.created_at`) divides by a window that may
  not contain those completions, producing scores > 1.0 or rewarding old
  habits that were just created.
- `MIN(completed_on)` is the earliest day the user actually performed the
  habit. For habits with zero completions, `MIN(...)` is NULL;
  `COALESCE(NULL, CURRENT_DATE)` makes the difference zero, then `GREATEST(1, 0)
  = 1`, then the numerator is 0 — final score is 0.0 / 1 = 0.0. Correct.
- For habits whose `MIN(completed_on)` is in the future (allowed by the API
  with no upper bound on `completed_on`), the subtraction is negative;
  `GREATEST(1, ...)` clamps it to 1.

**`GROUP BY` change:** `h.created_at` is removed from the GROUP BY because the
column is no longer in the SELECT projection. `MIN(hc.completed_on)` is an
aggregate so it does not appear in GROUP BY.

**Test compatibility:** the existing Phase 1 test
`habitConsistencyRanking should return habits sorted descending by score` seeds
five completions for `habit1` over five consecutive days ending today, and one
completion for `habit2` today. Under the new formula:
- `habit1`: 5 completions / max(1, today − (today − 4 days)) = 5 / 4 = 1.25
- `habit2`: 1 completion / max(1, today − today) = 1 / 1 = 1.0

`habit1` still outranks `habit2`. The existing assertion
(`ranking.map(_._1) shouldBe List("High-consistency", "Low-consistency")` plus
`ranking.head._2 should be > ranking(1)._2`) passes unchanged. **The test file
is not modified** (HARD LIMIT), only the production query is.

This change applies to **both** the `/insights` and `/analysis` endpoints
because they share `AnalyticsRepository.habitConsistencyRanking` (AC-23).

### 4. `HabitContext` field types — `Map[UUID, Int]` and `Map[UUID, Double]` are correct; `momentumScore` returns `IO[Double]`

The Phase 2 brief shows `Map[Long, Int]` / `Map[Long, Double]`. The brief is
wrong; `habits.id` is `UUID` (ADR-002, ADR-005, Phase 1 implementation in
`Habit.scala`). The Phase 1 `HabitContext` already uses `Map[UUID, Int]` for
`streaks`, and Phase 2 must follow.

**Final `HabitContext` shape (six fields, three Phase 1 + three Phase 2,
preserving Phase 1 ordering and types per AC-4):**

```scala
final case class HabitContext(
    userId:             Long,
    streaks:            Map[UUID, Int],
    completionByDay:    Map[String, Double],
    consistencyRanking: List[(String, Double)],
    timeOfDayPatterns:  Map[String, Double],
    correlatedPairs:    List[(String, String, Double)],
    momentumScores:     Map[UUID, Double]
)
```

The three Phase 2 fields are appended; the four Phase 1 fields are
byte-identical in name, type, and order. `AC-4` ("no removed or renamed
fields") is satisfied by appending.

**Repository signatures (the three new methods on `AnalyticsRepository`):**

```scala
def timeOfDaySuccessPattern(userId: Long): IO[Map[String, Double]]
def crossHabitCorrelation(userId: Long): IO[List[(String, String, Double)]]
def momentumScore(userId: Long, habitId: UUID): IO[Double]
```

`momentumScore` takes `userId: Long` *and* `habitId: UUID`. `userId` is
included so the SQL can join through `habits.user_id` to enforce ownership at
the SQL layer (matches `streakForHabit`'s design except scoped tighter — see
note below). `habitId` is the per-habit selector. The return type is
`IO[Double]` (single scalar, not a tuple).

User-scoping note for `momentumScore`: unlike Phase 1's `streakForHabit` which
trusts the caller to verify ownership and is scoped only by `habit_id`,
`momentumScore` is called for *every habit returned by
`habitRepo.listActive(userId)`* — i.e. the caller has already filtered to
this user's habits. Including `user_id` in the SQL `WHERE` clause is a
defensive belt-and-braces measure, costing one indexed column comparison and
buying us double-protection against any future caller that bypasses
`listActive`. The scaladoc on the method records this rationale.

### 5. Parallel query execution — `parTupled` for the 6-query assembly, `parTraverse` for the per-habit momentum fan-out

`buildHabitContext(userId: Long): IO[HabitContext]` runs **six logical
operations** plus a per-habit fan-out. They split into three groups:

- **Group A — habits-list dependency:** `habitRepo.listActive(userId)` runs
  first because two downstream queries (`streaks` per-habit and
  `momentumScores` per-habit) depend on its result.
- **Group B — independent user-scoped aggregates:** `byDay`, `ranking`,
  `timeOfDay`, `correlated` — four queries that take only `userId` and have
  no inter-dependencies. These run in parallel via `parTupled`.
- **Group C — per-habit fan-outs:** `streakForHabit(habitId)` and
  `momentumScore(userId, habitId)` for each `habit` in the active list. These
  run in parallel via `parTraverse`.

**Concrete implementation in `DefaultAnalyticsService`:**

```scala
import cats.effect.IO
import cats.syntax.all._   // for parTupled, parTraverse

override def buildHabitContext(userId: Long): IO[HabitContext] =
  for {
    habits <- habitRepo.listActive(userId)

    // Group B — four independent user-scoped queries in parallel
    aggregates <- (
      analyticsRepo.completionRateByDayOfWeek(userId),
      analyticsRepo.habitConsistencyRanking(userId),
      analyticsRepo.timeOfDaySuccessPattern(userId),
      analyticsRepo.crossHabitCorrelation(userId)
    ).parTupled
    (byDay, ranking, timeOfDay, correlated) = aggregates

    // Group C — per-habit fan-outs, each habit's two queries also in parallel
    perHabit <- habits.parTraverse { h =>
      (
        analyticsRepo.streakForHabit(h.id),
        analyticsRepo.momentumScore(userId, h.id)
      ).parTupled.map { case (streak, momentum) =>
        (h.id -> streak, h.id -> momentum)
      }
    }
    streaks         = perHabit.map(_._1).toMap
    momentumScores  = perHabit.map(_._2).toMap
  } yield HabitContext(
    userId             = userId,
    streaks            = streaks,
    completionByDay    = byDay,
    consistencyRanking = ranking,
    timeOfDayPatterns  = timeOfDay,
    correlatedPairs    = correlated,
    momentumScores     = momentumScores
  )
```

**Why `parTupled` for the four aggregates:** the four queries are independent,
each takes only `userId`, and the result types are heterogeneous
(`Map[String, Double]`, `List[(String, Double)]`, `Map[String, Double]`,
`List[(String, String, Double)]`). `parTupled` on a `Tuple4[IO[A], IO[B],
IO[C], IO[D]]` runs them concurrently and yields `IO[(A, B, C, D)]`. The
alternative `parMapN((a,b,c,d) => HabitContext(...))` is rejected because we
also need the per-habit fan-out result, so we cannot construct the
`HabitContext` until both parallel groups have completed.

**Why `parTraverse` for the per-habit fan-out:** `habits.parTraverse` runs the
inner `IO` for every habit concurrently and returns `IO[List[...]]`.
`parSequence` would work for `List[IO[X]]` but `parTraverse(f)` is more direct
when we are also computing `f(h)` from each habit. The inner per-habit
`(streakForHabit, momentumScore).parTupled` runs the two per-habit queries
concurrently — they share a habit but read different tables and rows.

**Sequential execution rejected:** Phase 1's service ran four operations
sequentially (one repo call per `<-` step). Phase 2 runs eight repo calls plus
a per-habit fan-out; sequential execution would multiply latency by 8+
round-trips. The Doobie `Transactor` is backed by a HikariCP pool with the
default 10-connection sizing in `DatabaseConfig`, so up to 10 of these queries
can run concurrently without contention.

### 6. `PromptBuilder` is a new standalone object in `com.habittracker.prompt`, not merged into `InsightPrompt`

**File:** `backend/src/main/scala/com/habittracker/prompt/PromptBuilder.scala`.

**Why a separate object, not a merge into `InsightPrompt`:**

- `InsightPrompt.scala` is a HARD LIMIT carry-over file (AC-9). It cannot be
  modified.
- The two prompts serve different LLM use cases (CLAUDE.md "LLM use cases"
  list): `InsightPrompt` is for the Phase 1 quick-summary insights endpoint;
  `PromptBuilder` is for the Phase 2 deep-coaching analysis endpoint. The
  system prompts are different (`SYSTEM_PROMPT` vs `HABIT_COACH_SYSTEM_PROMPT`),
  the section count is different (3 vs 6), and the framing is different
  ("be concise, 3–5 sentences" vs "4–6 sentences grouped into observations
  and one recommendation"). Co-locating them in one file would invite
  accidental coupling.
- Phase 3 will add `retrievedContextSection` to `PromptBuilder` (the brief's
  "Carry-over to Phase 3" block). Keeping `PromptBuilder` separate now means
  Phase 3 edits one file, not two.

**Filter-empty pattern:** every section method returns `String`. When the
relevant `HabitContext` field is empty (e.g. `correlatedPairs.isEmpty`,
`momentumScores.isEmpty`), the method returns `""`. `build` filters them out:

```scala
object PromptBuilder {

  val HABIT_COACH_SYSTEM_PROMPT: String =
    """You are a habit coach. You receive structured data about a user's
      |habit patterns and provide specific, actionable behavioural insights.
      |Always reference specific habit names and data points.
      |Format: 4-6 sentences grouped into observations and one recommendation."""
      .stripMargin

  def streakSection(ctx: HabitContext): String       = ...
  def dayPatternSection(ctx: HabitContext): String   = ...
  def rankingSection(ctx: HabitContext): String      = ...
  def timeOfDaySection(ctx: HabitContext): String    = ...
  def correlationSection(ctx: HabitContext): String  = ...
  def momentumSection(ctx: HabitContext): String     = ...

  def build(ctx: HabitContext): String =
    List(
      streakSection(ctx),
      dayPatternSection(ctx),
      rankingSection(ctx),
      timeOfDaySection(ctx),
      correlationSection(ctx),
      momentumSection(ctx)
    ).filter(_.nonEmpty).mkString("\n\n")
}
```

This matches the four-element header-and-three-sections pattern in
`InsightPrompt.build` (which uses `List(header, streakSection, daySection,
rankingSection).mkString("\n\n")`) — but `PromptBuilder.build` adds the
`.filter(_.nonEmpty)` because Phase 2 has sections (correlation, momentum)
that genuinely have nothing to say for users with one habit or insufficient
data.

**Empty-data handling per section** (exact rules the Developer must implement;
each is independently unit-tested):

| Section | Empty-data input | Returns |
|---|---|---|
| `streakSection` | `ctx.streaks.isEmpty` | `""` |
| `dayPatternSection` | `ctx.completionByDay.values.forall(_ == 0.0)` | `""` |
| `rankingSection` | `ctx.consistencyRanking.isEmpty` | `""` |
| `timeOfDaySection` | `ctx.timeOfDayPatterns.values.forall(_ == 0.0)` | `""` |
| `correlationSection` | `ctx.correlatedPairs.isEmpty` | `""` |
| `momentumSection` | `ctx.momentumScores.isEmpty` | `""` |

**Purity:** every method takes only `HabitContext` and returns `String`. No
`F[_]`, no `IO`, no side effects (no logging, no time reads, no `sys.env`).
This matches AC-6 and AC-8.

### 7. `/analysis` lives in a new `AnalysisRoutes` class, not in `InsightsRoutes`

**Decision:** create
`backend/src/main/scala/com/habittracker/http/AnalysisRoutes.scala` as a new
class with a single route. `InsightsRoutes.scala` is **not modified**.

**Why a new class, not an addition to `InsightsRoutes`:**

- `InsightsRoutes` is a Phase 1 carry-over surface and PBI AC-14 requires
  `/insights` to keep returning HTTP 200 with `InsightResponse` unchanged.
  Adding a second case branch into the same `HttpRoutes.of[IO] { ... }` block
  introduces edit risk to the Phase 1 route handler — git-diff and reviewer
  ergonomics worsen.
- `AnalysisRoutes` and `InsightsRoutes` have the same constructor shape
  (`AnalyticsService`) and the same dependencies (`AnthropicClient` directly,
  `PromptBuilder` / `InsightPrompt` directly), but they target different LLM
  use cases. Keeping them in sibling classes mirrors the prompt split (one
  prompt file per use case).
- ADR-008 Section 4 already anticipated this split: *"Phase 2 adds
  `PromptBuilder.scala` ... and a new analytics method on `AnalyticsRepository`
  without touching Phase 1 files. ... Adding Phase 2's `/analysis` endpoint is
  a local edit to that class or a parallel `AnalysisRoutes`."* The "parallel
  `AnalysisRoutes`" branch is taken here.

**Exact route body:**

```scala
package com.habittracker.http

import cats.effect.IO
import com.habittracker.client.AnthropicClient
import com.habittracker.http.AnalyticsCodecs._
import com.habittracker.model.AnalysisResponse
import com.habittracker.prompt.PromptBuilder
import com.habittracker.service.AnalyticsService
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._

/** Single route: GET /users/{userId}/habits/analysis.
  *
  * Anthropic HTTP call is made directly in this handler — no wrapping trait.
  * See ADR-008 (Phase 1) for the no-abstraction rationale and ADR-009 (this
  * ADR) for the route-placement decision. */
final class AnalysisRoutes(service: AnalyticsService) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "users" / LongVar(userId) / "habits" / "analysis" =>
      for {
        ctx       <- service.buildHabitContext(userId)
        prompt    =  PromptBuilder.build(ctx)
        narrative <- AnthropicClient.complete[IO](
                       PromptBuilder.HABIT_COACH_SYSTEM_PROMPT,
                       prompt
                     )
        response  =  AnalysisResponse(analytics = ctx, narrative = narrative)
        result    <- Ok(response)
      } yield result
  }
}
```

**`AppResources` wiring** (sole modification — no other code in `AppResources`
changes):

```scala
allRoutes = new DocsRoutes().routes <+>
            new InsightsRoutes(analyticsService).routes <+>
            new AnalysisRoutes(analyticsService).routes <+>     // NEW
            new HabitRoutes(habitService).routes <+>
            new HabitCompletionRoutes(completionSvc).routes
```

The same `analyticsService` instance is shared between `InsightsRoutes` and
`AnalysisRoutes` because both endpoints call `buildHabitContext(userId)` —
the new Phase 2 fields on `HabitContext` are populated either way; the
Phase 1 `/insights` route ignores the three new fields when constructing
its narrative (it goes through `InsightPrompt.build` which only reads the
four Phase 1 fields). `InsightResponse.analytics` will now serialise the full
six-field `HabitContext` — this is allowed because clients are free to
ignore extra JSON fields (and AC-14 only requires HTTP 200 + valid
`InsightResponse` body, not a byte-for-byte JSON match).

### 8. `AnalysisResponse` codecs go into `AnalyticsCodecs.scala` via semiauto, reusing the existing UUID `KeyEncoder`/`KeyDecoder`

`AnalysisResponse` is added to `Analytics.scala` (the existing model file)
alongside `HabitContext` and `InsightResponse`:

```scala
final case class AnalysisResponse(
    analytics: HabitContext,
    narrative: String
)
```

Codecs are added to the existing `AnalyticsCodecs` object using
`io.circe.generic.semiauto.deriveEncoder` / `deriveDecoder`:

```scala
implicit val analysisResponseEncoder: Encoder[AnalysisResponse] =
  deriveEncoder[AnalysisResponse]
implicit val analysisResponseDecoder: Decoder[AnalysisResponse] =
  deriveDecoder[AnalysisResponse]
```

The `habitContextEncoder` / `habitContextDecoder` already in `AnalyticsCodecs`
**re-derive** automatically when the case class gains three new fields —
semiauto pulls the current shape at compile time, so no manual edit is needed
for the existing `HabitContext` codecs *aside from* re-running the derivation
(which happens on recompile). The four new types appearing in those codecs are:

- `Map[String, Double]` — circe-core has `Encoder` / `Decoder` for `Double`
  and `String` already.
- `List[(String, String, Double)]` — circe encodes 3-tuples as JSON arrays of
  three elements via the built-in `Tuple3` codecs.
- `Map[UUID, Double]` — re-uses the existing `uuidKeyEncoder` /
  `uuidKeyDecoder` already declared in `AnalyticsCodecs` (no redefinition).

`HabitContext`'s extended encoder/decoder must re-derive after the case
class is updated. **Order of operations in the Developer's branch:** modify
`Analytics.scala` first (add three fields + `AnalysisResponse`), then modify
`AnalyticsCodecs.scala` (add `analysisResponseEncoder` / `Decoder`); the
existing `habitContextEncoder` / `Decoder` lines are unchanged textually but
re-derive on recompile.

### 9. `CompletionCodecs` reverify — semiauto re-derives, but tests must cover `completedAt`

`CreateHabitCompletionRequest` and `HabitCompletionResponse` codecs in
`CompletionCodecs.scala` are derived via `deriveEncoder` / `deriveDecoder`
(semiauto). Adding `completedAt: Option[Instant]` to those case classes
re-derives the codec automatically; **no edit to `CompletionCodecs.scala`
itself is required.**

`Encoder[Instant]` and `Decoder[Instant]` are provided transitively by
`io.circe.Encoder.encodeInstant` / `Decoder.decodeInstant` (in circe-core).
The existing `HabitCompletionCodecsSpec` round-trip tests must be **extended
(not duplicated)** with two cases:
- a request with `completedAt` populated round-trips correctly;
- a request without `completedAt` (key missing in JSON) decodes as `None`.

The existing tests in `HabitCompletionCodecsSpec` still pass because the
new field is `Option[Instant]` — circe's semiauto derivation makes
`Option` fields optional in the JSON.

### 10. Build tool is Gradle (`./gradlew`), not sbt — re-iterating from ADR-008 §7

The Phase 2 brief STACK section says `Build: sbt`. This is documentation
drift inherited from the Phase 1 brief and was already corrected in ADR-008
§7. ADR-009 re-iterates so the ADR chain has the correction at the same
place where future agents will look for Phase 2 decisions. All Developer
commands in PLAN-014 use `./gradlew compileScala` and `./gradlew test`.

The Phase 2 brief STACK section also says `Testing: munit-cats-effect,
CatsEffectSuite`. This is also documentation drift — the codebase uses
ScalaTest `AnyWordSpec` + `@RunWith(classOf[JUnitRunner])` throughout, as
recorded in the Phase 1 retrospective Section 4. Phase 2 tests follow the
same pattern. The Developer must not introduce munit.

### 11. Testing strategy — new vs extended specs, `@Ignore` discipline, regression verification

**New test files (Phase 2):**

| File | Type | `@Ignore` |
|---|---|---|
| `backend/src/test/scala/com/habittracker/prompt/PromptBuilderSpec.scala` | Pure unit (`AnyWordSpec`) | No |

**Extended test files (Phase 2 adds new test cases without modifying existing ones):**

| File | Phase 2 additions |
|---|---|
| `backend/src/test/scala/com/habittracker/repository/DoobieAnalyticsRepositorySpec.scala` | `timeOfDaySuccessPattern`, `crossHabitCorrelation` (2 cases), `momentumScore` (2 cases) — five new test methods. `@Ignore` retained. |
| `backend/src/test/scala/com/habittracker/http/HabitCompletionCodecsSpec.scala` | Two new round-trip cases for `completedAt` (with value, without value). |

**Phase 1 test files that must not be touched (HARD LIMIT):**

- `InsightPromptSpec.scala`
- `DoobieAnalyticsRepositorySpec.scala`'s existing three test methods
  (`streakForHabit` × 2, `completionRateByDayOfWeek` × 1,
  `habitConsistencyRanking` × 1) remain byte-for-byte identical. Phase 2
  appends new test methods at the bottom of the same `class` body — adding
  is allowed by AC-15 ("Phase 1 test files pass without modification" =
  existing assertions and seeds must run unchanged). The existing
  `habitConsistencyRanking` test passes under the new SQL formula because
  the relative ordering of `habit1` vs `habit2` is preserved (see §3 above).

**`@Ignore` discipline:** all Testcontainers-backed specs continue to
carry `@Ignore` per the Phase 1 convention recorded in ADR-008 §6. The
new Phase 2 test methods on `DoobieAnalyticsRepositorySpec` inherit the
class-level `@Ignore`; they run only when the engineer manually removes
the annotation and starts Docker. CI passes because `./gradlew test`
treats `@Ignore`d specs as skipped, not failed.

**Phase 1 `/insights` regression (AC-14):** the Reviewer agent runs
`./gradlew test` (which exercises pure unit and codec tests for the Phase 1
path) and additionally inspects the post-implementation diff to confirm
that `InsightsRoutes.scala`, `InsightPrompt.scala`, `AnthropicClient.scala`,
and `InsightPromptSpec.scala` are byte-identical to their pre-Phase-2
versions (`git diff main -- backend/src/main/scala/com/habittracker/http/InsightsRoutes.scala
backend/src/main/scala/com/habittracker/prompt/InsightPrompt.scala
backend/src/main/scala/com/habittracker/client/AnthropicClient.scala
backend/src/test/scala/com/habittracker/prompt/InsightPromptSpec.scala`
must produce no output).

End-to-end live verification of `/insights` HTTP 200 (with a real Anthropic
key) is performed manually by the engineer — same as the Phase 1
acceptance check, recorded in the Phase 1 retrospective.

### 12. OpenAPI spec update — three new schema fields, one new endpoint

The OpenAPI spec at `backend/src/main/resources/openapi/openapi.yaml` is
the source of truth for the wire format (per CLAUDE.md). Phase 2 changes:

- `HabitContext` gains three properties: `timeOfDayPatterns` (object,
  additionalProperties number), `correlatedPairs` (array of 3-tuples),
  `momentumScores` (object, additionalProperties number).
- New schema `AnalysisResponse` mirroring `InsightResponse`.
- New path `/users/{userId}/habits/analysis` with operationId
  `getHabitAnalysis`, returning `AnalysisResponse`.
- `CreateHabitCompletionRequest.completedAt` and
  `HabitCompletionResponse.completedAt` (string, format date-time, nullable).

Exact YAML diff is in PLAN-014 § "OpenAPI changes".

---

## Consequences

**Easier:**
- Adding a Phase 3 RAG section is a local edit to `PromptBuilder` (add
  `retrievedContextSection`, extend `build` signature). Neither
  `InsightPrompt` nor `AnthropicClient` is touched.
- `AnalysisRoutes` and `InsightsRoutes` evolve independently — adding
  filtering or query-parameter handling to `/analysis` does not touch
  Phase 1 code, and vice versa.
- The consistency-ranking bug fix lands once and is exercised by both
  endpoints because they share `AnalyticsRepository.habitConsistencyRanking`.
- The `completed_at` column is forward-compatible with future analytics
  (e.g. a Phase 3 "best hour to perform habit X" query) without another
  migration.

**Harder / trade-offs:**
- The `HabitContext` JSON returned by `/insights` now contains three Phase 2
  fields that the Phase 1 narrative does not reference. Downstream
  consumers that strict-decode the `/insights` response (rejecting unknown
  fields) would break. Circe's default decoder is permissive — extra fields
  are ignored — so existing clients are unaffected. This is an explicit
  acceptance of "wider response shape" rather than versioning the endpoint.
- `MIN(completed_on)` denominator means very-old past-date completions
  (e.g. a single completion three years ago) will deflate the consistency
  score for a habit that has otherwise been done daily for the last week.
  This is correct under the metric's intent ("how often, since you started")
  but may surprise users in edge cases. Documented for the BA agent's
  awareness; no code change.
- `AnalyticsRepository.momentumScore` runs once per habit, parallelised
  with `parTraverse`. A user with 50 active habits issues 50 concurrent
  momentum queries; HikariCP's default pool size (10) limits actual
  concurrency. This is acceptable for a PoC; if the user count grows,
  ADR-007's pool-sizing decision will need to be revisited.
- The Anthropic call still has no unit-level test (carrying the Phase 1
  trade-off forward — ADR-008 §2). The new `/analysis` endpoint inherits
  the same gap. Manual verification is the engineer's responsibility on
  every Phase 2 PR.

**Locked in:**
- `HabitContext` is now six fields. Phase 3 adds *one optional field*
  (`retrievedTips: List[String] = Nil`) per the carry-over block. No
  reorganisation of the existing six.
- `PromptBuilder` is the home for new prompt sections. Phase 3 adds
  `retrievedContextSection` and threads `tips` through `build`; existing
  six section methods unchanged.
- `AnalyticsRepository` is the home for new analytical SQL. Phase 3 adds
  embedding-aware retrieval methods; the trait grows but the existing six
  methods unchanged.
- `AnalysisRoutes` and `InsightsRoutes` are sibling classes. Future analytics
  endpoints get their own class file rather than expanding either of these.
- `completed_at` is `TIMESTAMPTZ NULL`. A future PBI that wants
  `NOT NULL` semantics for new rows must add a CHECK constraint or
  application-level validation; it cannot retroactively backfill historical
  rows without a destructive guess.
- Build remains Gradle. munit is not introduced. Codecs are semiauto in
  `AnalyticsCodecs` and `CompletionCodecs`.
