# PBI-010: List Completions for a Habit

## User story
As a user, I want to view all recorded completions for a specific habit, so
that I can review my history and understand my progress over time.

## Acceptance criteria
- [ ] `GET /habits/{habitId}/completions` returns a JSON array of completion
      records for the identified habit, ordered by `completedOn` descending
      (most recent first).
- [ ] Each element in the array contains: `id` (UUID), `habitId` (UUID),
      `completedOn` (ISO 8601 date), `note` (string or null),
      `createdAt` (ISO 8601 timestamp).
- [ ] If the habit identified by `{habitId}` does not exist or has been
      soft-deleted, the endpoint returns HTTP `404 Not Found`.
- [ ] If `{habitId}` is not a valid UUID format, the endpoint returns
      HTTP `400 Bad Request` with a descriptive error message.
- [ ] If the habit exists but has no completions, the endpoint returns
      HTTP `200 OK` with an empty JSON array `[]`.
- [ ] An optional `from` query parameter (ISO 8601 date, e.g. `?from=2026-01-01`)
      filters results to completions on or after that date.
- [ ] An optional `to` query parameter (ISO 8601 date, e.g. `?to=2026-04-17`)
      filters results to completions on or before that date.
- [ ] If `from` or `to` is provided but cannot be parsed as a valid ISO 8601
      date, the endpoint returns HTTP `400 Bad Request` with a descriptive
      error message.
- [ ] An integration test exists that creates a habit, records multiple
      completions, and asserts that `GET /habits/{habitId}/completions` returns
      all of them in `completedOn` descending order.
- [ ] An integration test exists that uses the `from` and `to` query parameters
      and asserts that only completions within the specified date range are
      returned.
- [ ] An integration test exists that calls the endpoint for a non-existent
      habit and asserts HTTP `404`.
- [ ] An integration test exists that calls the endpoint for a habit with no
      completions and asserts HTTP `200` with body `[]`.
- [ ] `./gradlew test` passes.

## Out of scope
- Pagination (the PoC does not require cursor- or page-based pagination;
  all completions for a habit are returned in a single response).
- Cross-habit completion queries (querying completions across multiple habits
  at once is not required for this PBI).
- Aggregation or statistics endpoints (streak length, completion rate, etc. —
  those belong to the pattern-analysis LLM feature).
- Authentication or user identification.
- Any frontend work.

## Notes
- Depends on PBI-008 (domain model and schema must exist first).
- Depends on PBI-009 (the endpoint is most usefully tested with real
  completion data, although the domain model from PBI-008 is the strict
  dependency).
- The `from` / `to` filter parameters are included in this PBI because
  they are needed by the daily-tip RAG retrieval (pattern-analysis context
  window is a rolling date range). If the engineer considers this scope
  creep, they may defer the filters to a later PBI — mark as out of scope
  in that case.
- The OpenAPI spec at
  `backend/src/main/resources/openapi/openapi.yaml` must be updated in
  the same PR to include the new endpoint, its query parameters, and all
  response codes (200, 400, 404).
- LLM use case relevance: this endpoint's query logic (date-range filter
  on completions for a habit) directly mirrors what the pattern-analysis
  and streak-risk-detection features will need when pulling context for
  RAG retrieval. A well-designed repository method here will be reused
  in those LLM features.

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
S
