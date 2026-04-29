package com.habittracker.service

import cats.effect.IO
import cats.syntax.all._
import com.habittracker.model.HabitContext
import com.habittracker.repository.{AnalyticsRepository, HabitRepository}

trait AnalyticsService {
  def buildHabitContext(userId: Long): IO[HabitContext]

  /** Derives a plain-text query string from the user's habit context for use
    * as the embedding query in the RAG tips pipeline. Pure: no IO, no F[_]. */
  def buildTipsQuery(ctx: HabitContext): String
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

  override def buildTipsQuery(ctx: HabitContext): String = {
    val top3 = ctx.consistencyRanking.take(3).map(_._1)
    val worstDays = ctx.completionByDay.toList
      .sortBy(_._2)
      .take(2)
      .map(_._1)

    if (top3.isEmpty || worstDays.size < 2) {
      "General habit-building practical advice."
    } else {
      val habitsStr = top3.mkString(", ")
      val worst1    = worstDays.head
      val worst2    = worstDays(1)
      s"Habits I'm working on: $habitsStr. I struggle most on $worst1 and $worst2. What practical advice helps?"
    }
  }
}
