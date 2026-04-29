package com.habittracker.http.dto

/** Response for the batch completions endpoint.
  *
  * Partial-success contract: items that were inserted appear in `inserted`;
  * items rejected by the unique constraint or a missing habit appear in
  * `skipped`. HTTP 200 is returned even when `inserted` is empty.
  * See ADR-010 §9. */
final case class BatchCompletionResponse(
    inserted: List[HabitCompletionResponse],
    skipped:  List[SkippedCompletion]
)
