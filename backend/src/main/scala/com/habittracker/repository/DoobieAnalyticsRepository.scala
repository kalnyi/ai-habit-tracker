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
              (CURRENT_DATE - COALESCE(MIN(hc.completed_on), CURRENT_DATE))::double precision
            )
        )::double precision AS score
      FROM habits h
      LEFT JOIN habit_completions hc ON hc.habit_id = h.id
      WHERE h.user_id = $userId AND h.deleted_at IS NULL
      GROUP BY h.id, h.name
      ORDER BY score DESC, h.name ASC
    """.query[(String, Double)]

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
