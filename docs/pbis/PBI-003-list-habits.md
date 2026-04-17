# PBI-003: List Habits

## User story
As a user, I want to retrieve a list of all my active habits, so that I can
see what I am currently tracking.

## Acceptance criteria
- [ ] `GET /habits` returns HTTP `200 OK` with a JSON array of habit objects.
- [ ] Each habit object in the response includes: `id`, `name`, `description`
      (null if not set), `frequency`, `created_at`, `updated_at`.
- [ ] Soft-deleted habits (where `deleted_at` IS NOT NULL) are excluded from
      the response.
- [ ] When no active habits exist, the endpoint returns `200 OK` with an empty
      JSON array `[]`, not a `404`.
- [ ] An integration test exists that seeds two active habits and one
      soft-deleted habit, calls `GET /habits`, and asserts that exactly the two
      active habits are returned.
- [ ] `./gradlew test` passes.

## Out of scope
- Pagination (deferred — the PoC has a single user with a small number of habits).
- Filtering or sorting beyond the implicit `deleted_at IS NULL` filter.
- Authentication or user identification.
- Any frontend work.

## Notes
- Depends on PBI-001 (domain model and schema).
- Depends on the framework ADR being resolved.
- Pagination is explicitly deferred; the Architect should note this as a
  future extension point in the technical plan (e.g., by returning a JSON
  object with a `data` array rather than a bare array, to avoid a breaking
  change when pagination is added). Engineer to confirm preferred response
  envelope shape before implementation.

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
XS
