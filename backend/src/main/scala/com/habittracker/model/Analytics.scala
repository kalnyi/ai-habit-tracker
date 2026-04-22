package com.habittracker.model

import java.util.UUID

final case class HabitContext(
    userId:             Long,
    streaks:            Map[UUID, Int],
    completionByDay:    Map[String, Double],
    consistencyRanking: List[(String, Double)]
)

final case class InsightResponse(
    analytics: HabitContext,
    narrative: String
)
