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
      streaks <- habits.traverse { h =>
        analyticsRepo.streakForHabit(h.id).map(s => h.id -> s)
      }.map(_.toMap)
      byDay   <- analyticsRepo.completionRateByDayOfWeek(userId)
      ranking <- analyticsRepo.habitConsistencyRanking(userId)
    } yield HabitContext(
      userId             = userId,
      streaks            = streaks,
      completionByDay    = byDay,
      consistencyRanking = ranking
    )
}
