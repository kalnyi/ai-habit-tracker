package com.habittracker.prompt

import com.habittracker.model.HabitContext

object InsightPrompt {

  val SYSTEM_PROMPT: String =
    """You are a habit analysis assistant. You receive structured data about
      |a user's habit completion patterns and provide specific, data-grounded
      |observations. Be concise: 3-5 sentences. Reference specific habit names
      |and numbers from the data provided.""".stripMargin

  def build(ctx: HabitContext): String = {
    val streakSection: String = {
      val lines = ctx.streaks.toList.map { case (habitId, streak) =>
        s"- habit $habitId: $streak-day current streak"
      }
      if (lines.isEmpty) "Current streaks:\n- (no active habits)"
      else "Current streaks:\n" + lines.mkString("\n")
    }

    val daySection: String = {
      val ordered = List(
        "Monday", "Tuesday", "Wednesday", "Thursday",
        "Friday", "Saturday", "Sunday"
      )
      val lines = ordered.map { day =>
        val rate = ctx.completionByDay.getOrElse(day, 0.0)
        f"- $day%-9s ${rate * 100}%.0f%% of weeks had at least one completion"
      }
      "Completion rate by day of week:\n" + lines.mkString("\n")
    }

    val rankingSection: String = {
      val lines = ctx.consistencyRanking.zipWithIndex.map { case ((name, score), i) =>
        f"${i + 1}. $name (score ${score}%.2f)"
      }
      if (lines.isEmpty) "Consistency ranking:\n- (no active habits)"
      else "Consistency ranking (descending):\n" + lines.mkString("\n")
    }

    val header: String = s"User ${ctx.userId} habit insights"

    List(header, streakSection, daySection, rankingSection).mkString("\n\n")
  }
}
