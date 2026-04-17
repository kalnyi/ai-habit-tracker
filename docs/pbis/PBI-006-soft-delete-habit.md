# PBI-006: Soft-Delete Habit

## User story
As a user, I want to delete a habit I no longer want to track, so that it
disappears from my active habit list while its historical data is preserved
for potential future analysis.

## Acceptance criteria
- [ ] `DELETE /habits/{id}` sets `deleted_at` to the current UTC timestamp for
      the matching habit record.
- [ ] On success the endpoint returns HTTP `204 No Content` with no response body.
- [ ] After a successful soft-delete, `GET /habits` no longer includes the
      deleted habit.
- [ ] After a successful soft-delete, `GET /habits/{id}` returns `404 Not Found`
      for the deleted habit.
- [ ] If no active habit with the given `id` exists (already deleted or never
      existed), the endpoint returns HTTP `404 Not Found`.
- [ ] If `{id}` is not a valid UUID, the endpoint returns HTTP `400 Bad Request`.
- [ ] The `habits` table row is NOT physically removed — the row must still be
      present in the database with `deleted_at` populated.
- [ ] An integration test exists that creates a habit, deletes it, and asserts
      `204`; then calls `GET /habits/{id}` and asserts `404`; and directly
      queries the database to confirm the row still exists with a non-null
      `deleted_at`.
- [ ] An integration test exists that calls `DELETE` on a non-existent ID and
      asserts `404`.
- [ ] `./gradlew test` passes.

## Out of scope
- Hard deletes (permanently prohibited in this project — soft delete only).
- Undelete / restore functionality.
- Cascading to completion records or embeddings (those do not exist yet).
- Authentication or user identification.
- Any frontend work.

## Notes
- Depends on PBI-001 (domain model and schema, which includes the `deleted_at`
  column).
- Depends on the framework ADR being resolved.
- Idempotency: a `DELETE` on an already-deleted habit returns `404`, not `204`.
  This is a deliberate simplification — the engineer may override this to
  return `204` idempotently if preferred, but the current spec is `404` to
  make the "not found" state explicit.
- The integration test that queries the database directly is important to
  verify the soft-delete constraint is enforced at the data layer, not only
  inferred from API behaviour.

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
S
