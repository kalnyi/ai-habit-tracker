# PLAN-014: Phase 2 — Habit Analysis Endpoint with Extended Context and Structured Prompt

## PBI reference
PBI-014: Phase 2 — Habit Analysis Endpoint with Extended Context and Structured
Prompt (`docs/phases/phase_2_pbi.md`); Phase brief
`docs/phases/phase_2_habit_analysis.md`. ADR pair: ADR-008 (Phase 1) and
ADR-009 (Phase 2).

## Summary
Two parallel work streams. **Stream A** adds a nullable
`completed_at TIMESTAMPTZ` column to `habit_completions` via Liquibase
changeset 005 and cascades the field through the `HabitCompletion` domain,
two completion DTOs, the Doobie repository (Read instance, INSERT, all
SELECTs), and the completion service. **Stream B** adds three new SQL queries
(`timeOfDaySuccessPattern`, `crossHabitCorrelation`, `momentumScore`) to the
existing `AnalyticsRepository`, fixes the `habitConsistencyRanking` denominator
bug to use `MIN(completed_on)`, extends `HabitContext` with three new fields,
adds a pure six-section `PromptBuilder` object alongside the unchanged
`InsightPrompt`, wires a new `AnalysisRoutes` class for
`GET /users/{userId}/habits/analysis`, and updates `AnalyticsCodecs`,
`AppResources`, and `openapi.yaml`. Six independent SQL operations are composed
via `parTupled` and `parTraverse` so context assembly runs in parallel.

## Preconditions / notes to the Developer agent

- **Build tool is Gradle.** Phase 2 brief and Done When checklist say
  `sbt test`; this is documentation drift (ADR-009 §10 and ADR-008 §7). All
  commands below use `./gradlew compileScala` and `./gradlew test`. Do not
  introduce sbt or munit. Tests use ScalaTest `AnyWordSpec` +
  `@RunWith(classOf[JUnitRunner])`.
- **ADR-009 is required reading** before writing any file in this plan.
  Twelve decisions are locked there: nullable column with documented NULL
  semantics, append-not-insert field ordering for `HabitCompletion`,
  `MIN(completed_on)` denominator, `Map[UUID, _]` types, `parTupled` +
  `parTraverse` parallel execution, `PromptBuilder` standalone in
  `com.habittracker.prompt`, `AnalysisRoutes` as new sibling class,
  `AnalysisResponse` codecs in existing `AnalyticsCodecs.scala`,
  `CompletionCodecs` reverify, Gradle correction, testing strategy with
  `@Ignore` discipline, OpenAPI update.
- **HARD LIMITS — files that must remain byte-for-byte identical:**
  `AnthropicClient.scala`, `InsightPrompt.scala`, `InsightsRoutes.scala`,
  `InsightPromptSpec.scala`, plus the existing test methods inside
  `DoobieAnalyticsRepositorySpec.scala` (Phase 2 appends new test methods
  but does not modify the four existing ones). Verify with `git diff main --
  <path>` producing no output for each.
- **Existing `HabitContext` fields are not touched.** The three new fields
  are appended in the order specified by AC-3 (`timeOfDayPatterns`,
  `correlatedPairs`, `momentumScores`). Preserving append-order keeps the
  semiauto codec stable and the JSON shape predictable.
- **`habitId` keys in `Map[UUID, _]`** — never `Long`. The Phase 2 brief
  shows `Map[Long, ...]`; this is wrong (ADR-009 §4).
- **Effect type is `IO` everywhere in routes and services.** No `F[_]:
  Async` parameter on `AnalyticsService` or `AnalysisRoutes`. The only place
  `F[_]` appears in this plan is `AnthropicClient.complete`, and the call
  site uses `complete[IO]` exactly once.
- **Anthropic model constant is `claude-sonnet-4-20250514`** — already set
  on `AnthropicClient.MODEL`. Do not change it.
- **`PromptBuilder.build` is a pure function.** No `IO`, no `F[_]`, no logging,
  no time reads, no `sys.env`. Same purity bar as `InsightPrompt.build`.
- **Codecs are `io.circe.generic.semiauto`.** Match the existing
  `AnalyticsCodecs` and `CompletionCodecs` style. Do not introduce
  `io.circe.generic.auto`.
- **No new external dependencies.** All required modules (Doobie,
  Cats Effect, Circe, sttp, http4s, ScalaTest, Testcontainers) are already
  on the classpath from Phase 1.
- **Order of file edits to keep the compiler green between commits:**
  1. Liquibase changeset 005 + master.
  2. `Analytics.scala` (add 3 fields + `AnalysisResponse`).
  3. `AnalyticsRepository.scala` (add 3 method signatures).
  4. `DoobieAnalyticsRepository.scala` (add 3 query implementations + fix
     `habitConsistencyRankingQuery`).
  5. `HabitCompletion.scala` (add `completedAt`).
  6. `DoobieHabitCompletionRepository.scala` (Read + INSERT + 2 SELECTs).
  7. `CreateHabitCompletionRequest.scala` (add `completedAt`).
  8. `HabitCompletionResponse.scala` (add `completedAt` + mapper).
  9. `HabitCompletionService.scala` (pass `completedAt` through).
  10. `AnalyticsService.scala` (extend `buildHabitContext` with parallel ops).
  11. `PromptBuilder.scala` (new file).
  12. `AnalyticsCodecs.scala` (add `AnalysisResponse` codec).
  13. `AnalysisRoutes.scala` (new file).
  14. `AppResources.scala` (wire `AnalysisRoutes`).
  15. `openapi.yaml` (extend `HabitContext`, add `AnalysisResponse`, add
      `/analysis` path, extend completion schemas).
  16. Test files (new `PromptBuilderSpec`, extend
      `DoobieAnalyticsRepositorySpec`, extend `HabitCompletionCodecsSpec`).

  After step 4, run `./gradlew compileScala` to confirm the schema-cascade
  files compile against the new `Analytics.scala`. After step 14, run
  `./gradlew compileScala` for the full source set. After step 16, run
  `./gradlew test`.

## Affected files

| File | Change type | Description |
|---|---|---|
| `infra/db/changelog/changesets/005-add-completed-at-to-habit-completions.sql` | Create | Liquibase changeset adding `completed_at TIMESTAMPTZ NULL`. |
| `infra/db/changelog/db.changelog-master.xml` | Modify | Append `<include>` for changeset 005. |
| `backend/src/main/scala/com/habittracker/model/Analytics.scala` | Modify | Append 3 fields to `HabitContext`; add `AnalysisResponse`. |
| `backend/src/main/scala/com/habittracker/repository/AnalyticsRepository.scala` | Modify | Add 3 method signatures. |
| `backend/src/main/scala/com/habittracker/repository/DoobieAnalyticsRepository.scala` | Modify | Add 3 query implementations; replace `habitConsistencyRankingQuery` body with `MIN(completed_on)` formula. |
| `backend/src/main/scala/com/habittracker/domain/HabitCompletion.scala` | Modify | Append `completedAt: Option[Instant]`. |
| `backend/src/main/scala/com/habittracker/repository/DoobieHabitCompletionRepository.scala` | Modify | Update `Read[HabitCompletion]`, `insertQuery`, `findByHabitAndDateQuery`, `listByHabitQuery` to include `completed_at`. |
| `backend/src/main/scala/com/habittracker/http/dto/CreateHabitCompletionRequest.scala` | Modify | Append `completedAt: Option[Instant]`. |
| `backend/src/main/scala/com/habittracker/http/dto/HabitCompletionResponse.scala` | Modify | Append `completedAt: Option[Instant]`; update `fromHabitCompletion`. |
| `backend/src/main/scala/com/habittracker/service/HabitCompletionService.scala` | Modify | In `recordCompletion`, set `HabitCompletion.completedAt = req.completedAt`. |
| `backend/src/main/scala/com/habittracker/service/AnalyticsService.scala` | Modify | Extend `buildHabitContext` with `parTupled` (4-aggregate group) and `parTraverse` (per-habit group). |
| `backend/src/main/scala/com/habittracker/prompt/PromptBuilder.scala` | Create | New pure object; 6 named section methods + `HABIT_COACH_SYSTEM_PROMPT` + `build`. |
| `backend/src/main/scala/com/habittracker/http/AnalyticsCodecs.scala` | Modify | Add `analysisResponseEncoder` / `Decoder`. |
| `backend/src/main/scala/com/habittracker/http/AnalysisRoutes.scala` | Create | New class with single route `GET /users/{userId}/habits/analysis`. |
| `backend/src/main/scala/com/habittracker/AppResources.scala` | Modify | Wire `new AnalysisRoutes(analyticsService).routes` into `allRoutes`. |
| `backend/src/main/resources/openapi/openapi.yaml` | Modify | Extend `HabitContext` and completion schemas; add `AnalysisResponse`; add `/analysis` path. |
| `backend/src/test/scala/com/habittracker/prompt/PromptBuilderSpec.scala` | Create | Pure unit tests for 6 sections + `build` + constant + filter-empty behaviour. |
| `backend/src/test/scala/com/habittracker/repository/DoobieAnalyticsRepositorySpec.scala` | Modify | Append five new test methods (the four Phase 1 methods unchanged). |
| `backend/src/test/scala/com/habittracker/http/HabitCompletionCodecsSpec.scala` | Modify | Append two new round-trip cases for `completedAt`. |

**Files explicitly NOT modified:**
- `backend/src/main/scala/com/habittracker/client/AnthropicClient.scala` (HARD LIMIT)
- `backend/src/main/scala/com/habittracker/prompt/InsightPrompt.scala` (HARD LIMIT)
- `backend/src/main/scala/com/habittracker/http/InsightsRoutes.scala` (HARD LIMIT)
- `backend/src/test/scala/com/habittracker/prompt/InsightPromptSpec.scala` (HARD LIMIT)
- `backend/src/main/scala/com/habittracker/http/CompletionCodecs.scala` (semiauto re-derives, no manual edit needed)
- All other Phase 1 / pre-existing files.

## New components

### `com.habittracker.model.Analytics` — extended

File: `backend/src/main/scala/com/habittracker/model/Analytics.scala`
(modified). Final shape:

```scala
package com.habittracker.model

import java.util.UUID

final case class HabitContext(
    userId:             Long,
    streaks:            Map[UUID, Int],
    completionByDay:    Map[String, Double],
    consistencyRanking: List[(String, Double)],
    timeOfDayPatterns:  Map[String, Double],
    correlatedPairs:    List[(String, String, Double)],
    momentumScores:     Map[UUID, Double]
)

final case class InsightResponse(
    analytics: HabitContext,
    narrative: String
)

final case class AnalysisResponse(
    analytics: HabitContext,
    narrative: String
)
```

Three new fields are appended; Phase 1 fields are unchanged in name, type,
and order. `InsightResponse` is unchanged. `AnalysisResponse` mirrors
`InsightResponse` (ADR-009 §8).

### `com.habittracker.repository.AnalyticsRepository` — extended trait

File: `backend/src/main/scala/com/habittracker/repository/AnalyticsRepository.scala`
(modified). Final shape (existing scaladoc preserved):

```scala
trait AnalyticsRepository {

  def streakForHabit(habitId: UUID): IO[Int]

  def completionRateByDayOfWeek(userId: Long): IO[Map[String, Double]]

  def habitConsistencyRanking(userId: Long): IO[List[(String, Double)]]

  // Phase 2 additions

  /** Rate of distinct days with at least one completion in each
    * time-of-day bucket, across all of the user's active habits. Buckets:
    * "morning" (06–11), "afternoon" (12–17), "evening" (18–21),
    * "night" (22–05). Completions whose `completed_at IS NULL` are
    * excluded from numerator and denominator. All four bucket keys are
    * returned even when the rate is 0.0. */
  def timeOfDaySuccessPattern(userId: Long): IO[Map[String, Double]]

  /** Top 3 habit pairs ranked by co-occurrence rate (fraction of distinct
    * days on which both habits were completed). Empty list when the user
    * has fewer than 2 active habits. Tuple shape: (habitA name, habitB
    * name, rate). */
  def crossHabitCorrelation(userId: Long): IO[List[(String, String, Double)]]

  /** Momentum score for a single habit:
    * completionRate(last 30 days) − completionRate(prior 30 days).
    * Returns 0.0 when either window contains fewer than 7 completions
    * (insufficient-data guard). Range: −1.0 to +1.0.
    *
    * `userId` is included for defensive double-scoping; the caller is
    * already expected to have filtered to habits owned by `userId`
    * (see scaladoc on the trait — same pattern as `streakForHabit`,
    * but with belt-and-braces SQL-level user check). */
  def momentumScore(userId: Long, habitId: UUID): IO[Double]
}
```

### `com.habittracker.repository.DoobieAnalyticsRepository` — extended

File:
`backend/src/main/scala/com/habittracker/repository/DoobieAnalyticsRepository.scala`
(modified).

**Replace** `habitConsistencyRankingQuery` body with the corrected formula:

```scala
private def habitConsistencyRankingQuery(userId: Long): Query0[(String, Double)] =
  sql"""
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
  """.query[(String, Double)]
```

Note `h.created_at` is removed from the GROUP BY (it is no longer in the
SELECT projection).

**Add** three new query helpers and three trait-method overrides:

```scala
// ---- timeOfDaySuccessPattern ----

private def timeOfDaySuccessPatternQuery(userId: Long): Query0[(String, Double)] =
  sql"""
    WITH user_completions AS (
      SELECT hc.completed_on, hc.completed_at
      FROM habit_completions hc
      JOIN habits h ON h.id = hc.habit_id
      WHERE h.user_id = $userId
        AND h.deleted_at IS NULL
        AND hc.completed_at IS NOT NULL
    ),
    bucketed AS (
      SELECT
        completed_on,
        CASE
          WHEN EXTRACT(HOUR FROM completed_at) BETWEEN  6 AND 11 THEN 'morning'
          WHEN EXTRACT(HOUR FROM completed_at) BETWEEN 12 AND 17 THEN 'afternoon'
          WHEN EXTRACT(HOUR FROM completed_at) BETWEEN 18 AND 21 THEN 'evening'
          ELSE 'night'
        END AS bucket
      FROM user_completions
    ),
    total_days AS (
      SELECT GREATEST(1, COUNT(DISTINCT completed_on)) AS denom
      FROM user_completions
    ),
    per_bucket AS (
      SELECT bucket, COUNT(DISTINCT completed_on) AS days
      FROM bucketed
      GROUP BY bucket
    )
    SELECT
      per_bucket.bucket,
      (per_bucket.days::double precision / total_days.denom)::double precision
    FROM per_bucket, total_days
  """.query[(String, Double)]

override def timeOfDaySuccessPattern(userId: Long): IO[Map[String, Double]] = {
  val bucketOrder = List("morning", "afternoon", "evening", "night")
  timeOfDaySuccessPatternQuery(userId).to[List].transact(transactor).map { rows =>
    val found = rows.toMap
    bucketOrder.map(b => b -> found.getOrElse(b, 0.0)).toMap
  }
}

// ---- crossHabitCorrelation ----

private def crossHabitCorrelationQuery(userId: Long): Query0[(String, String, Double)] =
  sql"""
    WITH user_habits AS (
      SELECT id, name
      FROM habits
      WHERE user_id = $userId AND deleted_at IS NULL
    ),
    pair_days AS (
      SELECT
        a.id AS a_id, a.name AS a_name,
        b.id AS b_id, b.name AS b_name,
        COUNT(DISTINCT hca.completed_on) AS both_days
      FROM user_habits a
      JOIN user_habits b ON a.id < b.id
      JOIN habit_completions hca ON hca.habit_id = a.id
      JOIN habit_completions hcb ON hcb.habit_id = b.id
                                 AND hcb.completed_on = hca.completed_on
      GROUP BY a.id, a.name, b.id, b.name
    ),
    union_days AS (
      SELECT
        a.id AS a_id,
        b.id AS b_id,
        COUNT(DISTINCT hc.completed_on) AS u_days
      FROM user_habits a
      JOIN user_habits b ON a.id < b.id
      JOIN habit_completions hc ON hc.habit_id IN (a.id, b.id)
      GROUP BY a.id, b.id
    )
    SELECT
      pd.a_name,
      pd.b_name,
      (pd.both_days::double precision / NULLIF(ud.u_days, 0))::double precision AS rate
    FROM pair_days pd
    JOIN union_days ud
      ON ud.a_id = pd.a_id AND ud.b_id = pd.b_id
    ORDER BY rate DESC NULLS LAST, pd.a_name ASC, pd.b_name ASC
    LIMIT 3
  """.query[(String, String, Double)]

override def crossHabitCorrelation(userId: Long): IO[List[(String, String, Double)]] =
  crossHabitCorrelationQuery(userId).to[List].transact(transactor)

// ---- momentumScore ----

private def momentumScoreQuery(userId: Long, habitId: UUID): Query0[Double] =
  sql"""
    WITH owned AS (
      SELECT h.id
      FROM habits h
      WHERE h.id = $habitId AND h.user_id = $userId AND h.deleted_at IS NULL
    ),
    last_30 AS (
      SELECT COUNT(*)::int AS n
      FROM habit_completions hc
      JOIN owned o ON o.id = hc.habit_id
      WHERE hc.completed_on > CURRENT_DATE - INTERVAL '30 day'
    ),
    prior_30 AS (
      SELECT COUNT(*)::int AS n
      FROM habit_completions hc
      JOIN owned o ON o.id = hc.habit_id
      WHERE hc.completed_on > CURRENT_DATE - INTERVAL '60 day'
        AND hc.completed_on <= CURRENT_DATE - INTERVAL '30 day'
    )
    SELECT
      CASE
        WHEN (SELECT n FROM last_30)  < 7 THEN 0.0
        WHEN (SELECT n FROM prior_30) < 7 THEN 0.0
        ELSE
          ((SELECT n FROM last_30)::double precision / 30.0)
          - ((SELECT n FROM prior_30)::double precision / 30.0)
      END
  """.query[Double]

override def momentumScore(userId: Long, habitId: UUID): IO[Double] =
  momentumScoreQuery(userId, habitId).unique.transact(transactor)
```

Notes on the SQL:
- `timeOfDaySuccessPattern` uses `EXTRACT(HOUR FROM completed_at)`. Hours
  22–05 wrap, so the `CASE` ends with `ELSE 'night'` (hours 22, 23, 0–5).
- `crossHabitCorrelation` uses `a.id < b.id` to avoid duplicate pairs.
  `NULLIF(ud.u_days, 0)` guards against zero-division for habits with zero
  completions; `ORDER BY rate DESC NULLS LAST` keeps such pairs at the
  bottom.
- `momentumScore` returns `0.0` when either 30-day window has fewer than 7
  completions, matching the AC-3 specification ("0.0 when insufficient data").

### `com.habittracker.domain.HabitCompletion` — extended

```scala
final case class HabitCompletion(
    id: UUID,
    habitId: UUID,
    completedOn: LocalDate,
    note: Option[String],
    createdAt: Instant,
    completedAt: Option[Instant]
)
```

### `com.habittracker.repository.DoobieHabitCompletionRepository` — extended

Three changes:

1. **`Read[HabitCompletion]` instance:**
   ```scala
   private implicit val completionRead: Read[HabitCompletion] =
     Read[(UUID, UUID, LocalDate, Option[String], Instant, Option[Instant])].map {
       case (id, habitId, completedOn, note, createdAt, completedAt) =>
         HabitCompletion(id, habitId, completedOn, note, createdAt, completedAt)
     }
   ```
2. **`insertQuery`:** add `completed_at` column and value:
   ```scala
   private def insertQuery(c: HabitCompletion): Update0 =
     sql"""
       INSERT INTO habit_completions (id, habit_id, completed_on, note, created_at, completed_at)
       VALUES (${c.id}, ${c.habitId}, ${c.completedOn}, ${c.note}, ${c.createdAt}, ${c.completedAt})
     """.update
   ```
3. **`findByHabitAndDateQuery` and `listByHabitQuery`:** add `, completed_at`
   to the SELECT projection (after `created_at`). Both queries use the same
   implicit `Read[HabitCompletion]`, so the column order in SELECT must match
   the `Read` tuple order.

### `com.habittracker.http.dto.CreateHabitCompletionRequest` — extended

```scala
final case class CreateHabitCompletionRequest(
    completedOn: LocalDate,
    note: Option[String],
    completedAt: Option[Instant]
)
```

Add `import java.time.Instant`.

### `com.habittracker.http.dto.HabitCompletionResponse` — extended

```scala
final case class HabitCompletionResponse(
    id: UUID,
    habitId: UUID,
    completedOn: LocalDate,
    note: Option[String],
    createdAt: Instant,
    completedAt: Option[Instant]
)

object HabitCompletionResponse {
  def fromHabitCompletion(c: HabitCompletion): HabitCompletionResponse =
    HabitCompletionResponse(
      id = c.id,
      habitId = c.habitId,
      completedOn = c.completedOn,
      note = c.note,
      createdAt = c.createdAt,
      completedAt = c.completedAt
    )
}
```

### `com.habittracker.service.HabitCompletionService` — extended

In `DefaultHabitCompletionService.recordCompletion`, extend the
`HabitCompletion(...)` construction to pass `completedAt = req.completedAt`:

```scala
completion = HabitCompletion(
  id = id,
  habitId = habitId,
  completedOn = req.completedOn,
  note = req.note,
  createdAt = now,
  completedAt = req.completedAt
)
```

No other change to the service file. The trait signature does not change.

### `com.habittracker.service.AnalyticsService` — extended `buildHabitContext`

```scala
package com.habittracker.service

import cats.effect.IO
import cats.syntax.all._
import com.habittracker.model.HabitContext
import com.habittracker.repository.{AnalyticsRepository, HabitRepository}

trait AnalyticsService {
  def buildHabitContext(userId: Long): IO[HabitContext]
}

final class DefaultAnalyticsService(
    habitRepo:     HabitRepository,
    analyticsRepo: AnalyticsRepository
) extends AnalyticsService {

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

      // Group C — per-habit fan-outs, each habit's two queries in parallel
      perHabit <- habits.parTraverse { h =>
        (
          analyticsRepo.streakForHabit(h.id),
          analyticsRepo.momentumScore(userId, h.id)
        ).parTupled.map { case (streak, momentum) =>
          (h.id -> streak, h.id -> momentum)
        }
      }
      streaks        = perHabit.map(_._1).toMap
      momentumScores = perHabit.map(_._2).toMap
    } yield HabitContext(
      userId             = userId,
      streaks            = streaks,
      completionByDay    = byDay,
      consistencyRanking = ranking,
      timeOfDayPatterns  = timeOfDay,
      correlatedPairs    = correlated,
      momentumScores     = momentumScores
    )
}
```

The trait signature is unchanged (`buildHabitContext(userId: Long):
IO[HabitContext]`). `InsightsRoutes` continues to call this same method —
the wider `HabitContext` JSON is irrelevant to it because
`InsightPrompt.build` only reads the four Phase 1 fields.

### `com.habittracker.prompt.PromptBuilder` — new file

File: `backend/src/main/scala/com/habittracker/prompt/PromptBuilder.scala`.

```scala
package com.habittracker.prompt

import com.habittracker.model.HabitContext

object PromptBuilder {

  val HABIT_COACH_SYSTEM_PROMPT: String =
    """You are a habit coach. You receive structured data about a user's
      |habit patterns and provide specific, actionable behavioural insights.
      |Always reference specific habit names and data points.
      |Format: 4-6 sentences grouped into observations and one recommendation."""
      .stripMargin

  // ---------------------------------------------------------------------------
  // Phase 1 sections — re-implemented here for the analysis use case
  // ---------------------------------------------------------------------------

  def streakSection(ctx: HabitContext): String = {
    if (ctx.streaks.isEmpty) ""
    else {
      val lines = ctx.streaks.toList.map { case (habitId, streak) =>
        s"- habit $habitId: $streak-day current streak"
      }
      "Current streaks:\n" + lines.mkString("\n")
    }
  }

  def dayPatternSection(ctx: HabitContext): String = {
    val ordered = List(
      "Monday", "Tuesday", "Wednesday", "Thursday",
      "Friday", "Saturday", "Sunday"
    )
    if (ctx.completionByDay.values.forall(_ == 0.0)) ""
    else {
      val lines = ordered.map { day =>
        val rate = ctx.completionByDay.getOrElse(day, 0.0)
        f"- $day%-9s ${rate * 100}%.0f%% of weeks had at least one completion"
      }
      "Completion rate by day of week:\n" + lines.mkString("\n")
    }
  }

  def rankingSection(ctx: HabitContext): String = {
    if (ctx.consistencyRanking.isEmpty) ""
    else {
      val lines = ctx.consistencyRanking.zipWithIndex.map { case ((name, score), i) =>
        f"${i + 1}. $name (score ${score}%.2f)"
      }
      "Consistency ranking (descending):\n" + lines.mkString("\n")
    }
  }

  // ---------------------------------------------------------------------------
  // Phase 2 sections
  // ---------------------------------------------------------------------------

  def timeOfDaySection(ctx: HabitContext): String = {
    val order = List("morning", "afternoon", "evening", "night")
    if (ctx.timeOfDayPatterns.values.forall(_ == 0.0)) ""
    else {
      val lines = order.map { bucket =>
        val rate = ctx.timeOfDayPatterns.getOrElse(bucket, 0.0)
        f"- $bucket%-9s ${rate * 100}%.0f%% of completion-days fall in this window"
      }
      "Time-of-day patterns:\n" + lines.mkString("\n")
    }
  }

  def correlationSection(ctx: HabitContext): String = {
    if (ctx.correlatedPairs.isEmpty) ""
    else {
      val lines = ctx.correlatedPairs.map { case (a, b, rate) =>
        f"- $a + $b: ${rate * 100}%.0f%% co-occurrence"
      }
      "Top co-occurring habit pairs:\n" + lines.mkString("\n")
    }
  }

  def momentumSection(ctx: HabitContext): String = {
    if (ctx.momentumScores.isEmpty) ""
    else {
      val lines = ctx.momentumScores.toList.map { case (habitId, score) =>
        f"- habit $habitId: momentum ${score}%+0.2f (last 30d − prior 30d)"
      }
      "Momentum (positive = improving, negative = declining):\n" +
        lines.mkString("\n")
    }
  }

  // ---------------------------------------------------------------------------
  // Build
  // ---------------------------------------------------------------------------

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

### `com.habittracker.http.AnalyticsCodecs` — extended

Add two lines (encoder + decoder for `AnalysisResponse`) under the existing
`InsightResponse` codecs:

```scala
implicit val analysisResponseEncoder: Encoder[AnalysisResponse] =
  deriveEncoder[AnalysisResponse]
implicit val analysisResponseDecoder: Decoder[AnalysisResponse] =
  deriveDecoder[AnalysisResponse]
```

Also add `AnalysisResponse` to the existing import:
```scala
import com.habittracker.model.{AnalysisResponse, HabitContext, InsightResponse}
```

The existing `habitContextEncoder` / `Decoder` re-derive automatically when
the `HabitContext` case class gains the three Phase 2 fields. The
`uuidKeyEncoder` / `uuidKeyDecoder` already declared on the object are
re-used by `Map[UUID, Double]` (the new `momentumScores` field) — no
redefinition.

### `com.habittracker.http.AnalysisRoutes` — new file

File: `backend/src/main/scala/com/habittracker/http/AnalysisRoutes.scala`.

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
  * Anthropic HTTP call is made directly in this handler — no wrapping trait,
  * no abstract class. See ADR-008 (Phase 1) for the no-abstraction rationale
  * and ADR-009 (Phase 2) for the route-placement decision (sibling class to
  * InsightsRoutes, not an addition to it). */
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

### `com.habittracker.AppResources` — extended

Add the import and route wiring:

```scala
import com.habittracker.http.{AnalysisRoutes, DocsRoutes, HabitCompletionRoutes, HabitRoutes, InsightsRoutes}
```

Within `AppResources.make`, the `allRoutes` value becomes:

```scala
allRoutes = new DocsRoutes().routes <+>
            new InsightsRoutes(analyticsService).routes <+>
            new AnalysisRoutes(analyticsService).routes <+>
            new HabitRoutes(habitService).routes <+>
            new HabitCompletionRoutes(completionSvc).routes
```

The `analyticsService` instance is shared between `InsightsRoutes` and
`AnalysisRoutes`. No new repositories or services are introduced.

## API contract

### New endpoint

**`GET /users/{userId}/habits/analysis`**

Path parameters:
- `userId` (integer, int64) — required.

Request body: none.

Response 200 OK:
```json
{
  "analytics": {
    "userId": 1,
    "streaks": { "<uuid>": 0 },
    "completionByDay": { "Monday": 0.0, "Tuesday": 0.0, "Wednesday": 0.0, "Thursday": 0.0, "Friday": 0.0, "Saturday": 0.0, "Sunday": 0.0 },
    "consistencyRanking": [],
    "timeOfDayPatterns": { "morning": 0.0, "afternoon": 0.0, "evening": 0.0, "night": 0.0 },
    "correlatedPairs": [],
    "momentumScores": { "<uuid>": 0.0 }
  },
  "narrative": "string"
}
```

Response 500: any failure of the Anthropic call or the underlying SQL
queries propagates as a 500 with the http4s default error handler. No
specific error body shape is mandated by Phase 2 (PBI Out-of-scope:
"Error handling beyond what Phase 1 established").

### Modified endpoints (request/response shape changes only)

**`POST /users/{userId}/habits/{habitId}/completions`**

`CreateHabitCompletionRequest` gains `completedAt` (string, format
date-time, nullable). Existing required field `completedOn` is unchanged;
existing optional field `note` is unchanged. Clients omitting `completedAt`
get a record with `completed_at = NULL` (valid for past-date completions
where the time is unknown).

**`HabitCompletionResponse`** (returned from POST and from
`GET /users/{userId}/habits/{habitId}/completions`)

Adds `completedAt` (string, format date-time, nullable). All existing
fields unchanged.

### Phase 1 endpoint contract (unchanged behaviour)

**`GET /users/{userId}/habits/insights`** continues to return HTTP 200
with `InsightResponse`. The `analytics` field now contains the three new
Phase 2 keys (`timeOfDayPatterns`, `correlatedPairs`, `momentumScores`)
populated from the same `buildHabitContext` call. The Phase 1 narrative
generated by `InsightPrompt.build` reads only the four Phase 1 fields, so
the user-visible string is unchanged. Clients that strict-decode
`InsightResponse` will see extra keys in `analytics`, which circe's
default decoder ignores — no client breakage.

## Database changes

### Liquibase changeset 005 (new file)

File:
`infra/db/changelog/changesets/005-add-completed-at-to-habit-completions.sql`.

```sql
--liquibase formatted sql

--changeset habit-tracker:005-add-completed-at-to-habit-completions
--comment: Adds optional completed_at TIMESTAMPTZ for time-of-day analysis. See ADR-009.

ALTER TABLE habit_completions
    ADD COLUMN completed_at TIMESTAMPTZ NULL;

--rollback ALTER TABLE habit_completions DROP COLUMN IF EXISTS completed_at;
```

### Master changelog (modified)

File: `infra/db/changelog/db.changelog-master.xml`. Append after the 004
include:

```xml
<include file="changesets/005-add-completed-at-to-habit-completions.sql" relativeToChangelogFile="true"/>
```

### Existing changesets

Changesets 001–004 are not modified. Project rule (CLAUDE.md): never edit
existing migration files.

## OpenAPI changes

Apply four diffs to `backend/src/main/resources/openapi/openapi.yaml`:

### Diff A — extend `CreateHabitCompletionRequest`

Add to `properties` (after `note`):
```yaml
        completedAt:
          type: string
          format: date-time
          nullable: true
```

### Diff B — extend `HabitCompletionResponse`

Add to `properties` (after `createdAt`):
```yaml
        completedAt:
          type: string
          format: date-time
          nullable: true
```
Do not add `completedAt` to `required` — it is optional.

### Diff C — extend `HabitContext`

Replace the existing `HabitContext` schema with:
```yaml
    HabitContext:
      type: object
      required:
        - userId
        - streaks
        - completionByDay
        - consistencyRanking
        - timeOfDayPatterns
        - correlatedPairs
        - momentumScores
      properties:
        userId:
          type: integer
          format: int64
        streaks:
          type: object
          additionalProperties:
            type: integer
        completionByDay:
          type: object
          additionalProperties:
            type: number
            format: double
        consistencyRanking:
          type: array
          items:
            type: array
            minItems: 2
            maxItems: 2
            items:
              oneOf:
                - type: string
                - type: number
                  format: double
        timeOfDayPatterns:
          type: object
          additionalProperties:
            type: number
            format: double
        correlatedPairs:
          type: array
          items:
            type: array
            minItems: 3
            maxItems: 3
            items:
              oneOf:
                - type: string
                - type: number
                  format: double
        momentumScores:
          type: object
          additionalProperties:
            type: number
            format: double
```

### Diff D — add `AnalysisResponse` schema and `/analysis` path

Add after `InsightResponse` schema:
```yaml
    AnalysisResponse:
      type: object
      required: [analytics, narrative]
      properties:
        analytics:
          $ref: '#/components/schemas/HabitContext'
        narrative:
          type: string
```

Add new path between `/users/{userId}/habits/insights` and the
`{completionId}` delete path:
```yaml
  /users/{userId}/habits/analysis:
    parameters:
      - name: userId
        in: path
        required: true
        schema:
          type: integer
          format: int64
    get:
      summary: Get LLM-generated multi-section behavioural analysis
      operationId: getHabitAnalysis
      responses:
        '200':
          description: Successful response with extended analytics and coaching narrative
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AnalysisResponse'
        '500':
          description: Upstream LLM failure or internal error
```

## LLM integration

This PBI implements the **pattern analysis** LLM use case from CLAUDE.md
("weekly summary of habit completion patterns, correlations between habits,
and risk flags") with three new data dimensions (time-of-day, cross-habit
correlation, momentum trends).

- **Use case:** `/analysis` endpoint, deeper than Phase 1 `/insights`.
- **Prompt file:** `PromptBuilder.scala` is the prompt module; system prompt
  constant is `PromptBuilder.HABIT_COACH_SYSTEM_PROMPT` (a `val` on the
  object). User message is `PromptBuilder.build(ctx)`.
- **RAG strategy:** none in Phase 2. All context comes from SQL queries.
  Phase 3 will add `retrievedTips` to `HabitContext` and a
  `retrievedContextSection` to `PromptBuilder`.
- **Model:** `claude-sonnet-4-20250514` (carry-over from
  `AnthropicClient.MODEL`, unchanged).
- **Prompt versioning:** the system prompt is a new constant on a new
  object. `docs/prompt-changelog.md` should record the addition (per
  CLAUDE.md "any change to a prompt requires a new version file and an
  entry in `docs/prompt-changelog.md`"). Treat `PromptBuilder.scala`
  itself as v1 of the analysis prompt; future prompt edits go into
  `PromptBuilder` with a versioned constant name (e.g.
  `HABIT_COACH_SYSTEM_PROMPT_V2`).

## Test plan

### New file: `PromptBuilderSpec`

File: `backend/src/test/scala/com/habittracker/prompt/PromptBuilderSpec.scala`.
Pure unit tests, ScalaTest `AnyWordSpec`, `@RunWith(classOf[JUnitRunner])`,
no `@Ignore`, no IO, no Docker.

Test cases (covering AC-12):

```
"PromptBuilder" should {
  "expose HABIT_COACH_SYSTEM_PROMPT as a non-empty constant" in { ... }

  "streakSection should return non-empty for a fully populated ctx" in { ... }
  "streakSection should return empty string when streaks is empty" in { ... }

  "dayPatternSection should return non-empty for a fully populated ctx" in { ... }
  "dayPatternSection should return empty string when all rates are 0.0" in { ... }

  "rankingSection should return non-empty for a fully populated ctx" in { ... }
  "rankingSection should return empty string when consistencyRanking is empty" in { ... }

  "timeOfDaySection should return non-empty for a fully populated ctx" in { ... }
  "timeOfDaySection should return empty string when all rates are 0.0" in { ... }

  "correlationSection should return non-empty for a fully populated ctx" in { ... }
  "correlationSection should return empty string when correlatedPairs is empty" in { ... }

  "momentumSection should return non-empty for a fully populated ctx" in { ... }
  "momentumSection should return empty string when momentumScores is empty" in { ... }

  "build" should {
    "contain content from all 6 sections when ctx is fully populated" in { ... }
    "filter out empty sections so blank lines do not appear in output" in { ... }
  }
}
```

Helpers: a `fullyPopulated: HabitContext` fixture with two habits, all 7
day rates non-zero, two ranking entries, all 4 time-of-day rates non-zero,
two correlated pairs, two momentum scores. An `empty: HabitContext`
fixture with empty maps/lists and all-zero day rates.

The "filter empty" assertion seeds a ctx where only `streaks` is non-empty
and verifies that `build(ctx)` does not contain a `\n\n\n` triple-newline
(no blank-section gaps) and equals `streakSection(ctx)`.

### Modified file: `DoobieAnalyticsRepositorySpec`

File: `backend/src/test/scala/com/habittracker/repository/DoobieAnalyticsRepositorySpec.scala`.

The class-level `@Ignore` is retained. The four existing test methods
(`streakForHabit` × 2, `completionRateByDayOfWeek` × 1,
`habitConsistencyRanking` × 1) remain byte-for-byte identical. **Append**
five new test methods (covering AC-11):

```
"timeOfDaySuccessPattern" should {
  "return correct rates for the 4 buckets and exclude NULL completed_at" in {
    // Seed: same habit, three completions on three distinct days:
    //   day1 with completed_at = 09:00 (morning)
    //   day2 with completed_at = 14:00 (afternoon)
    //   day3 with completed_at = NULL  (excluded)
    // Expected: morning = 1/2 = 0.5, afternoon = 1/2 = 0.5,
    //           evening = 0.0, night = 0.0
    // Verify all 4 keys present.
  }
}

"crossHabitCorrelation" should {
  "return the top pair when 3 habits have known co-occurrence" in {
    // Seed: 3 habits A, B, C
    //   A and B completed together on 4 distinct days (high co-occurrence)
    //   A and C completed together on 1 distinct day
    //   B and C completed together on 0 distinct days
    // Expected first element: ("A", "B", > 0.5)
  }

  "return empty list when the user has 1 habit" in {
    // Seed: 1 habit, 5 completions
    // Expected: List.empty
  }
}

"momentumScore" should {
  "return positive when last-30 has more completions than prior-30 (both >= 7)" in {
    // Seed: 12 completions in last 30 days (well-spaced),
    //       8 completions in prior 30 days
    // Expected: (12/30) - (8/30) = 0.133… > 0.0
  }

  "return 0.0 when fewer than 7 completions in either window" in {
    // Seed: 3 completions in last 30 days, 8 in prior 30 days
    // Expected: 0.0
  }
}
```

Use the existing `makeHabit`, `makeCompletion`, `run` helpers. For
`timeOfDaySuccessPattern`, extend `makeCompletion` calls to set
`completedAt` explicitly; alternative is to construct `HabitCompletion`
directly with the new sixth parameter.

### Modified file: `HabitCompletionCodecsSpec`

File: `backend/src/test/scala/com/habittracker/http/HabitCompletionCodecsSpec.scala`.
**Append** two new round-trip cases (existing tests unchanged):

```
"CreateHabitCompletionRequest codec" should {
  "decode a request with completedAt populated" in {
    val json = """{"completedOn":"2026-04-17","completedAt":"2026-04-17T09:30:00Z"}"""
    val result = decode[CreateHabitCompletionRequest](json)
    // assert Right with completedAt = Some(Instant.parse("2026-04-17T09:30:00Z")) and note = None
  }

  "decode a request without completedAt (treated as None)" in {
    val json = """{"completedOn":"2026-04-17"}"""
    decode[CreateHabitCompletionRequest](json) shouldBe Right(
      CreateHabitCompletionRequest(
        completedOn = LocalDate.of(2026, 4, 17),
        note = None,
        completedAt = None
      )
    )
  }
}
```

The existing assertions reference the old two-argument
`CreateHabitCompletionRequest(date, note)` constructor. **They must be
updated** to the three-argument form `CreateHabitCompletionRequest(date,
note, None)` because Scala 2.13 case-class apply takes positional
arguments. This is a mechanical update inside the existing tests, not a
change in test intent — it does not violate any HARD LIMIT (the
HARD LIMIT covers Phase 1 *production* `InsightPrompt` /
`AnthropicClient` / `InsightsRoutes` files and Phase 1 *test* file
`InsightPromptSpec`. `HabitCompletionCodecsSpec` and
`HabitCompletionServiceSpec` are not in the HARD LIMIT list.)

Same mechanical update applies to:
- `HabitCompletionServiceSpec` — every `CreateHabitCompletionRequest(date,
  note)` call gains a third positional arg `None`.
- `HabitCompletionRoutesSpec` (if it constructs the request) — same.
- `HabitCompletionApiIntegrationSpec` (if it constructs the request) — same.

The Developer should grep for `CreateHabitCompletionRequest(` in
`backend/src/test/` and add `, None` to each call site that previously
used two arguments. Same for `HabitCompletionResponse(...)` in
`HabitCompletionCodecsSpec` (the round-trip helpers) — add a sixth
positional arg `None` (or `Some(Instant.parse(...))` for the new
populated-`completedAt` round-trip case).

### Phase 1 regression coverage (AC-14, AC-15)

Verification steps the Developer must run before declaring done:
1. `git diff main -- backend/src/main/scala/com/habittracker/client/AnthropicClient.scala
                      backend/src/main/scala/com/habittracker/prompt/InsightPrompt.scala
                      backend/src/main/scala/com/habittracker/http/InsightsRoutes.scala
                      backend/src/test/scala/com/habittracker/prompt/InsightPromptSpec.scala`
   must produce no output.
2. `./gradlew compileScala` succeeds.
3. `./gradlew test` reports zero failures. Testcontainers specs (carrying
   `@Ignore`) are counted as skipped.
4. Manual live-API check (engineer-run): unset `ANTHROPIC_API_KEY` and
   verify `./gradlew run` raises the expected startup error;
   set the key and verify both `GET /users/1/habits/insights` and
   `GET /users/1/habits/analysis` return HTTP 200 with valid JSON bodies
   (matches the Phase 1 manual-acceptance pattern recorded in the Phase 1
   retrospective).

## ADRs required

ADR-009 written and saved at
`docs/adr/ADR-009-phase2-habit-analysis.md` as part of this plan.

No other new ADRs required. ADRs 001–008 remain authoritative; ADR-009
extends ADR-008 (Phase 1) without contradicting any prior ADR.

## Open questions

1. **`MIN(completed_on)` denominator semantics for very old completions.**
   The fix in §3 of ADR-009 means a habit with one three-year-old
   completion plus six recent completions has its consistency score
   deflated by the long tail. The PBI requires the formula change (AC-23)
   so the implementation proceeds, but the engineer should be aware that
   future BA discussions of "what does consistency mean" may want to revisit
   this. **No code change requested in this plan; raise to BA if a follow-up
   PBI is needed.**

2. **Doobie Hikari pool size and 50-habit-user worst case.** ADR-009
   §11 (Consequences) flags that a user with 50 active habits issues 50
   concurrent `momentumScore` queries through `parTraverse`. HikariCP's
   default pool size in `DatabaseConfig` is 10 (per ADR-007). For PoC
   scale this is fine; flag for future capacity work but **no change in
   this plan**.

3. **Should the `/insights` JSON response continue to expose the three
   new `HabitContext` fields, or should it return a narrowed projection?**
   ADR-009 §7 records the decision to leave `/insights` exposing the wider
   shape (clients ignore unknown fields by default). If product wants a
   strictly Phase-1 projection on `/insights`, a follow-up PBI would
   introduce a separate `Phase1HabitContext` view type. **No change in
   this plan.**

This technical plan is ready for your review. Please approve or request
changes before I hand off to the Developer agent.
