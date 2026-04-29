package com.habittracker.repository

import cats.effect.IO

import java.util.UUID

/** Read-only analytical queries.
  *
  * User-scoping note: `streakForHabit(habitId: UUID)` is scoped by `habit_id`
  * only. The caller — typically `AnalyticsService` — is responsible for
  * verifying that the habit belongs to the user in question via
  * `HabitRepository.listActive(userId)` before calling here. This is a
  * deliberate divergence from `HabitRepository`, which enforces
  * `WHERE user_id = ?` on every SELECT (see ADR-007 and ADR-008). */
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
