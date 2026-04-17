# PBI-009: Record a Habit Completion

## User story
As a user, I want to mark a habit as completed on a specific date, so that
my progress is recorded and available for analysis and streak tracking.

## Acceptance criteria
- [ ] `POST /habits/{habitId}/completions` accepts a JSON body with the
      following fields:
      `completedOn` (string, required — ISO 8601 date, e.g. `"2026-04-17"`),
      `note` (string, optional — free-text annotation).
- [ ] On success the endpoint returns HTTP `201 Created` with a JSON body
      containing: `id` (UUID), `habitId` (UUID), `completedOn` (ISO 8601 date),
      `note` (string or null), `createdAt` (ISO 8601 timestamp).
- [ ] If `{habitId}` is not a valid UUID format, the endpoint returns
      HTTP `400 Bad Request` with a descriptive error message.
- [ ] If the habit identified by `{habitId}` does not exist or has been
      soft-deleted, the endpoint returns HTTP `404 Not Found`.
- [ ] If `completedOn` is missing or cannot be parsed as a valid ISO 8601 date,
      the endpoint returns HTTP `400 Bad Request` with a descriptive error message.
- [ ] If the habit has already been marked as completed on the same `completedOn`
      date, the endpoint returns HTTP `409 Conflict` with a descriptive error
      message (duplicate completion for the same day is not permitted).
- [ ] The completion is persisted to the `habit_completions` table with the
      provided values and a server-generated `id` (UUID) and `created_at`
      (current UTC timestamp).
- [ ] An integration test exists that creates a habit, then POSTs a valid
      completion and asserts HTTP `201` with all expected response fields present.
- [ ] An integration test exists that attempts to record a completion for a
      non-existent habit and asserts HTTP `404`.
- [ ] An integration test exists that records a completion, then attempts to
      record a second completion for the same habit and same date, and asserts
      HTTP `409`.
- [ ] An integration test exists that POSTs an invalid `completedOn` value
      and asserts HTTP `400`.
- [ ] `./gradlew test` passes.

## Out of scope
- Listing or querying completions (covered in PBI-010).
- Deleting a completion record (deferred to PBI-011).
- Authentication or user identification.
- Any frontend work.
- Validation that `completedOn` is not in the future (no such constraint
  is required for the PoC).
- Idempotent upsert behaviour — a duplicate completion on the same day is
  a `409`, not a silent no-op.

## Notes
- Depends on PBI-008 (domain model and schema must exist first).
- The `409 Conflict` error case maps to a new `AppError` variant or a
  re-use of an existing one — the Architect must ensure `ConflictError`
  (or equivalent) is present in the `AppError` sealed trait and is mapped
  to HTTP `409` in `ErrorHandler`.
- The OpenAPI spec at
  `backend/src/main/resources/openapi/openapi.yaml` must be updated in
  the same PR to include the new endpoint, its request body, and all
  response codes (201, 400, 404, 409).
- LLM use case relevance: the `note` field on a completion is the primary
  source of free-text data that will be embedded into pgvector for RAG
  retrieval when the daily-tip and pattern-analysis features are built.
  The schema and API design should not restrict `note` length beyond what
  PostgreSQL TEXT allows.

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
S
