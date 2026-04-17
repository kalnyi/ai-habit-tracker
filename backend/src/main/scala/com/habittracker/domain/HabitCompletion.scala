package com.habittracker.domain

import java.time.{Instant, LocalDate}
import java.util.UUID

final case class HabitCompletion(
    id: UUID,
    habitId: UUID,
    completedOn: LocalDate,
    note: Option[String],
    createdAt: Instant
)
