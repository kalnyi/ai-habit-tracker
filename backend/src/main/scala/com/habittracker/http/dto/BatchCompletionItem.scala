package com.habittracker.http.dto

import java.time.{Instant, LocalDate}
import java.util.UUID

/** Request item for the batch completions endpoint.
  *
  * Unlike `CreateHabitCompletionRequest`, this DTO carries `habitId` because
  * the batch endpoint spans multiple habits in one call (no habitId path
  * parameter). See ADR-010 §9. */
final case class BatchCompletionItem(
    habitId:     UUID,
    completedOn: LocalDate,
    note:        Option[String],
    completedAt: Option[Instant]
)
