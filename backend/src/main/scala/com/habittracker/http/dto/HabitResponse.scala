package com.habittracker.http.dto

import com.habittracker.domain.{Frequency, Habit}

import java.time.Instant
import java.util.UUID

final case class HabitResponse(
    id: UUID,
    name: String,
    description: Option[String],
    frequency: String,
    createdAt: Instant,
    updatedAt: Instant
)

object HabitResponse {
  def fromHabit(h: Habit): HabitResponse =
    HabitResponse(
      id = h.id,
      name = h.name,
      description = h.description,
      frequency = Frequency.asString(h.frequency),
      createdAt = h.createdAt,
      updatedAt = h.updatedAt
    )
}
