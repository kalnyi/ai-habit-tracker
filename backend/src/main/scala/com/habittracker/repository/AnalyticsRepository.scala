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
}
