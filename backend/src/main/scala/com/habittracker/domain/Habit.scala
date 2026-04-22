package com.habittracker.domain

import java.time.Instant
import java.util.UUID

final case class Habit(
    id: UUID,
    userId: Long,
    name: String,
    description: Option[String],
    frequency: Frequency,
    createdAt: Instant,
    updatedAt: Instant,
    deletedAt: Option[Instant]
)
