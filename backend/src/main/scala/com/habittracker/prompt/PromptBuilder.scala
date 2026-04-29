package com.habittracker.prompt

import com.habittracker.model.{HabitContext, RetrievedTip}

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
        f"- habit $habitId: momentum ${score}%+.2f (last 30d - prior 30d)"
      }
      "Momentum (positive = improving, negative = declining):\n" +
        lines.mkString("\n")
    }
  }

  // ---------------------------------------------------------------------------
  // Phase 3: RAG retrieved-context section
  // ---------------------------------------------------------------------------

  // Grounding means injecting retrieved external knowledge into the prompt so
  // the LLM synthesises its response from those specific facts rather than
  // relying solely on patterns absorbed during training. A "cold" (ungrounded)
  // prompt asks the LLM to reason from general knowledge; a grounded prompt
  // anchors it in the retrieved tips that are most semantically relevant to
  // this particular user's habits and context.
  def retrievedContextSection(tips: List[RetrievedTip]): String =
    if (tips.isEmpty) ""
    else {
      val lines = tips.map { rt =>
        s"- ${rt.tip.content}"
      }
      "Relevant habit-science tips retrieved for this user (use as supporting evidence, not verbatim):\n" +
        lines.mkString("\n")
    }

  // ---------------------------------------------------------------------------
  // Phase 4: personal-notes section
  // ---------------------------------------------------------------------------

  /** Renders the user's own past notes retrieved from user_notes.
    *
    * Header is "YOUR PAST NOTES:" (distinct from retrievedContextSection's
    * "Relevant habit-science tips..." preamble) so the LLM can distinguish
    * curated corpus content from user-authored content. Returns "" when
    * `notes` is Nil — filtered out by `build`. See ADR-011 §8. */
  def personalNotesSection(notes: List[RetrievedTip]): String =
    if (notes.isEmpty) ""
    else {
      val lines = notes.map { rn => s"- ${rn.tip.content}" }
      "YOUR PAST NOTES:\n" + lines.mkString("\n")
    }

  // ---------------------------------------------------------------------------
  // Build — Phase 4 widened signature
  // ---------------------------------------------------------------------------

  /** Phase 4: both `tips` and `notes` default to Nil so existing call sites
    * (AnalysisRoutes' `PromptBuilder.build(ctx)` and any Phase 3
    * `PromptBuilder.build(ctx, tips)` callers) continue to compile. */
  def build(
      ctx:   HabitContext,
      tips:  List[RetrievedTip] = Nil,
      notes: List[RetrievedTip] = Nil
  ): String =
    List(
      streakSection(ctx),
      dayPatternSection(ctx),
      rankingSection(ctx),
      timeOfDaySection(ctx),
      correlationSection(ctx),
      momentumSection(ctx),
      retrievedContextSection(tips),
      personalNotesSection(notes)
    ).filter(_.nonEmpty).mkString("\n\n")
}
