package com.habittracker.http.dto

import java.time.LocalDate

final case class CreateHabitCompletionRequest(
    completedOn: LocalDate,
    note: Option[String]
)
