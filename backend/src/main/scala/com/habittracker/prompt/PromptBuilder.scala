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
