package com.habittracker.repository

import cats.effect.IO
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.util.UUID

final class DoobieAnalyticsRepository(transactor: Transactor[IO])
    extends AnalyticsRepository {

  // ---------------------------------------------------------------------------
  // SQL queries
  // ---------------------------------------------------------------------------

  private def streakForHabitQuery(habitId: UUID): Query0[Int] =
    sql"""
      WITH RECURSIVE streak(day) AS (
        SELECT CURRENT_DATE
        WHERE EXISTS (
          SELECT 1 FROM habit_completions
          WHERE habit_id = $habitId AND completed_on = CURRENT_DATE
        )
        UNION ALL
        SELECT (streak.day - INTERVAL '1 day')::date
        FROM streak
        WHERE EXISTS (
          SELECT 1 FROM habit_completions
          WHERE habit_id = $habitId
            AND completed_on = (streak.day - INTERVAL '1 day')::date
        )
      )
      SELECT COUNT(*)::int FROM streak
    """.query[Int]

  private def completionRateByDayOfWeekQuery(userId: Long): Query0[(String, Double)] =
    sql"""
      WITH user_completions AS (
        SELECT hc.completed_on
        FROM habit_completions hc
        JOIN habits h ON h.id = hc.habit_id
        WHERE h.user_id = $userId AND h.deleted_at IS NULL
      ),
      week_span AS (
        SELECT GREATEST(
          1,
          CEIL(
            (CURRENT_DATE - MIN(completed_on)) / 7.0
          )::int
        ) AS total_weeks
        FROM user_completions
      ),
      per_day AS (
        SELECT
          TO_CHAR(completed_on, 'FMDay') AS day_name,
          COUNT(DISTINCT DATE_TRUNC('week', completed_on)) AS weeks_with_completion
        FROM user_completions
        GROUP BY TO_CHAR(completed_on, 'FMDay')
      )
      SELECT
        per_day.day_name,
        (per_day.weeks_with_completion::double precision / week_span.total_weeks)::double precision
      FROM per_day, week_span
    """.query[(String, Double)]

  private def habitConsistencyRankingQuery(userId: Long): Query0[(String, Double)] =
    sql"""
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
    """.query[(String, Double)]

  // ---------------------------------------------------------------------------
  // AnalyticsRepository implementation
  // ---------------------------------------------------------------------------

  override def streakForHabit(habitId: UUID): IO[Int] =
    streakForHabitQuery(habitId).unique.transact(transactor)

  override def completionRateByDayOfWeek(userId: Long): IO[Map[String, Double]] = {
    val dayOrder = List(
      "Monday", "Tuesday", "Wednesday", "Thursday",
      "Friday", "Saturday", "Sunday"
    )
    completionRateByDayOfWeekQuery(userId).to[List].transact(transactor).map { rows =>
      val found = rows.toMap
      dayOrder.map(d => d -> found.getOrElse(d, 0.0)).toMap
    }
  }

  override def habitConsistencyRanking(userId: Long): IO[List[(String, Double)]] =
    habitConsistencyRankingQuery(userId).to[List].transact(transactor)
}
