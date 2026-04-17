# PBI-004: Get Single Habit

## User story
As a user, I want to retrieve the details of a specific habit by its ID, so
that I can review or reference it individually.

## Acceptance criteria
- [ ] `GET /habits/{id}` returns HTTP `200 OK` with a JSON object representing
      the habit when a matching active (non-deleted) habit exists.
- [ ] The response object includes: `id`, `name`, `description` (null if not
      set), `frequency`, `created_at`, `updated_at`.
- [ ] If no habit with the given `id` exists (or it has been soft-deleted),
      the endpoint returns HTTP `404 Not Found` with a JSON error body
      containing a `message` field.
- [ ] If `{id}` is not a valid UUID, the endpoint returns HTTP `400 Bad Request`
      with a descriptive error message.
- [ ] An integration test exists that creates a habit, fetches it by ID, and
      asserts the `200` response and correct field values.
- [ ] An integration test exists that requests a non-existent UUID and asserts
      the `404` response.
- [ ] `./gradlew test` passes.

## Out of scope
- Returning deleted habits (soft-deleted habits must not be surfaced via this
  endpoint).
- Authentication or user identification.
- Any frontend work.

## Notes
- Depends on PBI-001 (domain model and schema).
- Depends on the framework ADR being resolved.
- The error response shape must match the standard `ErrorResponse` defined
  during PBI-002 implementation.
- Soft-deleted habits returning `404` (rather than a distinct `410 Gone`) is
  an intentional simplification for the PoC.

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
XS
