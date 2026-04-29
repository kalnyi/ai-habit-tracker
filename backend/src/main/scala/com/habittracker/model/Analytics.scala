package com.habittracker.model

import java.util.UUID

final case class HabitContext(
    userId:             Long,
    streaks:            Map[UUID, Int],
    completionByDay:    Map[String, Double],
    consistencyRanking: List[(String, Double)],
    timeOfDayPatterns:  Map[String, Double],
    correlatedPairs:    List[(String, String, Double)],
    momentumScores:     Map[UUID, Double]
)

final case class InsightResponse(
    analytics: HabitContext,
    narrative: String
)

final case class AnalysisResponse(
    analytics: HabitContext,
    narrative: String
)
