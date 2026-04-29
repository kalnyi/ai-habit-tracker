package com.habittracker.model

import java.util.UUID

final case class HabitContext(
    userId:             Long,
    streaks:            Map[UUID, Int],
    completionByDay:    Map[String, Double],
    consistencyRanking: List[(String, Double)],
    timeOfDayPatterns:  Map[String, Double]              = Map.empty,
    correlatedPairs:    List[(String, String, Double)]   = Nil,
    momentumScores:     Map[UUID, Double]                = Map.empty,
    retrievedTips:      List[String]                     = Nil   // Phase 3: default Nil keeps frozen Phase 1/2 tests compiling
)

final case class InsightResponse(
    analytics: HabitContext,
    narrative: String
)

final case class AnalysisResponse(
    analytics: HabitContext,
    narrative: String
)

// ---------------------------------------------------------------------------
// Phase 3: RAG corpus types
// ---------------------------------------------------------------------------

/** A single tip stored in the habit_tips corpus table. */
final case class HabitTip(id: Long, content: String)

/** A tip retrieved from the corpus, paired with its cosine similarity score
  * relative to the user's query embedding. */
final case class RetrievedTip(tip: HabitTip, similarityScore: Double)

/** Response returned by GET /users/{userId}/habits/tips. */
final case class TipsResponse(tips: List[RetrievedTip], narrative: String)
