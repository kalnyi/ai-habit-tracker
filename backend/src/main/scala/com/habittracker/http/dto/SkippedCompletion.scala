package com.habittracker.http.dto

import java.time.LocalDate
import java.util.UUID

/** Represents a batch item that was not inserted, with the reason it was
  * skipped (e.g. duplicate, habit not found). See ADR-010 §9. */
final case class SkippedCompletion(
    habitId:     UUID,
    completedOn: LocalDate,
    reason:      String
)
