# PBI-005: Update Habit

## User story
As a user, I want to update an existing habit's name, description, or frequency,
so that I can correct or refine it over time.

## Acceptance criteria
- [ ] `PUT /habits/{id}` accepts a JSON body with the following fields:
      `name` (string, required), `description` (string, optional),
      `frequency` (string, required — `"daily"` or `"weekly"`).
- [ ] On success the endpoint returns HTTP `200 OK` with a JSON body
      representing the updated habit, including the refreshed `updated_at`
      timestamp.
- [ ] The `updated_at` timestamp in the database is updated to the current time
      on every successful `PUT`.
- [ ] If no active habit with the given `id` exists (or it has been
      soft-deleted), the endpoint returns HTTP `404 Not Found`.
- [ ] If `{id}` is not a valid UUID, the endpoint returns HTTP `400 Bad Request`.
- [ ] If `name` is missing or blank, the endpoint returns HTTP `400 Bad Request`
      with a descriptive error message.
- [ ] If `frequency` is not one of the accepted values, the endpoint returns
      HTTP `400 Bad Request` with a descriptive error message.
- [ ] An integration test exists that creates a habit, updates its name, and
      asserts the `200` response, the changed name, and a strictly later
      `updated_at` than the original.
- [ ] An integration test exists that attempts to update a non-existent habit
      and asserts `404`.
- [ ] `./gradlew test` passes.

## Out of scope
- Partial updates / PATCH semantics (full replacement with PUT is sufficient
  for the PoC).
- Updating `deleted_at` directly (that is covered by PBI-006).
- Authentication or user identification.
- Any frontend work.

## Notes
- Depends on PBI-001 (domain model and schema) and PBI-002 (error response
  shape convention).
- Depends on the framework ADR being resolved.
- The PUT replaces all mutable fields; fields not present in the body should
  be treated as a validation error, not silently ignored.
- `created_at` and `id` must not be modified by this endpoint.

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
S
