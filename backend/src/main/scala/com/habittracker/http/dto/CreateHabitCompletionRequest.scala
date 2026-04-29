package com.habittracker.http.dto

import java.time.{Instant, LocalDate}

final case class CreateHabitCompletionRequest(
    completedOn: LocalDate,
    note: Option[String],
    completedAt: Option[Instant]
)
