package com.habittracker.http.dto

import com.habittracker.domain.HabitCompletion

import java.time.{Instant, LocalDate}
import java.util.UUID

final case class HabitCompletionResponse(
    id: UUID,
    habitId: UUID,
    completedOn: LocalDate,
    note: Option[String],
    createdAt: Instant
)

object HabitCompletionResponse {
  def fromHabitCompletion(c: HabitCompletion): HabitCompletionResponse =
    HabitCompletionResponse(
      id = c.id,
      habitId = c.habitId,
      completedOn = c.completedOn,
      note = c.note,
      createdAt = c.createdAt
    )
}
