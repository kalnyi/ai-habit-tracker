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
