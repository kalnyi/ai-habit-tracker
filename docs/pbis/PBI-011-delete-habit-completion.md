# PBI-011: Delete a Habit Completion

## User story
As a user, I want to remove a completion I recorded in error, so that my
history accurately reflects my actual progress.

## Acceptance criteria
- [ ] `DELETE /habits/{habitId}/completions/{completionId}` permanently removes
      the identified completion record from the `habit_completions` table (hard
      delete — there is no soft-delete requirement for completions).
- [ ] On success the endpoint returns HTTP `204 No Content` with no response body.
- [ ] If `{habitId}` is not a valid UUID format, the endpoint returns
      HTTP `400 Bad Request` with a descriptive error message.
- [ ] If `{completionId}` is not a valid UUID format, the endpoint returns
      HTTP `400 Bad Request` with a descriptive error message.
- [ ] If the habit identified by `{habitId}` does not exist or has been
      soft-deleted, the endpoint returns HTTP `404 Not Found`.
- [ ] If the completion identified by `{completionId}` does not exist, or
      does not belong to the specified `{habitId}`, the endpoint returns
      HTTP `404 Not Found`.
- [ ] An integration test exists that creates a habit, records a completion,
      deletes that completion, and asserts HTTP `204`; then calls
      `GET /habits/{habitId}/completions` and asserts the deleted record is
      absent.
- [ ] An integration test exists that calls `DELETE` with a non-existent
      `completionId` and asserts HTTP `404`.
- [ ] An integration test exists that calls `DELETE` with a valid
      `completionId` that belongs to a different `habitId` and asserts
      HTTP `404` (ownership check).
- [ ] `./gradlew test` passes.

## Out of scope
- Soft-delete for completions — completion records use hard delete only.
- Bulk deletion of multiple completions in a single request.
- Authentication or user identification.
- Any frontend work.

## Notes
- Depends on PBI-008 (domain model and schema) and PBI-009 (record
  completion) being implemented first, as the integration tests require
  real completion records.
- Hard delete is intentional for completion records: they carry no
  audit or analytical value once the user has declared them erroneous.
  This is explicitly different from habits, which use soft delete to
  preserve historical context.
- The ownership check (`completionId` must belong to `habitId`) is
  enforced in the repository query (`WHERE id = ? AND habit_id = ?`),
  not at the service layer, to avoid an extra round-trip.
- The OpenAPI spec at
  `backend/src/main/resources/openapi/openapi.yaml` must be updated in
  the same PR to include the new endpoint and all response codes
  (204, 400, 404).

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
XS
