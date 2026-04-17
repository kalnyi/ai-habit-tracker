# PBI-007: Serve OpenAPI Specification and Swagger UI

## User story
As a developer integrating with the Habit Tracker API, I want a Swagger UI
served by the backend and a machine-readable OpenAPI specification available
at a stable URL, so that I can explore, understand, and manually test all
available endpoints without reading source code.

## Acceptance criteria
- [ ] A Swagger UI is accessible at `GET /docs` in a running backend instance
      and renders correctly in a standard browser (Chrome or Firefox).
- [ ] The OpenAPI specification is accessible as a single self-contained
      document at `GET /docs/openapi.json` (JSON) and/or
      `GET /docs/openapi.yaml` (YAML) — at least one format must be served.
- [ ] The specification declares OpenAPI version 3.0.x or 3.1.x.
- [ ] All five existing endpoints are documented in the spec:
      `POST /habits`, `GET /habits`, `GET /habits/{id}`,
      `PUT /habits/{id}`, `DELETE /habits/{id}`.
- [ ] For each endpoint, the spec includes: HTTP method, path, a brief
      summary, all path and query parameters with their types, the request
      body schema where applicable, and all documented response codes with
      their schemas.
- [ ] The `frequency` field on request/response schemas is documented as an
      enum with exactly the values `"daily"` and `"weekly"`.
- [ ] The `ErrorResponse` shape (`{ "message": "..." }`) is referenced as the
      schema for all error responses (400, 404) across every endpoint.
- [ ] The `/docs` and `/docs/openapi.*` routes are present only when the app
      is not running in a designated production environment (i.e. the routes
      can be gated by `APP_ENV`), OR they are always exposed — this behaviour
      must be explicitly decided and documented in the ADR triggered by this PBI.
- [ ] An integration test verifies that `GET /docs/openapi.json` (or `.yaml`)
      returns HTTP `200` with the correct `Content-Type` header.
- [ ] `gradle test` passes with the new test included.

## Out of scope
- Authentication or API key protection on the `/docs` route.
- Documenting any endpoints that do not yet exist (LLM tips, pattern analysis,
  streak risk, etc.).
- Interactive "Try it out" requests that require a live database — the UI
  may offer this functionality, but making it work end-to-end is not required.
- Code generation from the spec (client SDKs, server stubs).
- Frontend integration or a link to `/docs` from any UI.
- Pagination, filtering, or query-parameter documentation beyond what is
  currently implemented.

## Notes
- **ADR required.** The choice of how to produce the OpenAPI spec (code-first
  annotation-driven generation vs hand-authored YAML/JSON checked into the
  repo vs a hybrid) and which library (if any) serves the Swagger UI is an
  architectural decision with meaningful trade-offs for Akka HTTP 10.5.3 /
  Scala 2.13. The Architect agent must produce an ADR before implementation
  begins.
- The existing `ErrorResponse`, `CreateHabitRequest`, `UpdateHabitRequest`,
  and `HabitResponse` DTOs (already in the codebase) must be reflected
  accurately in the spec — the spec and the code must not diverge.
- No authentication is currently implemented; the spec should not declare any
  security schemes.
- Depends on PBI-001 through PBI-006 being implemented (the endpoints being
  documented must exist).
- The `APP_ENV` environment variable is already defined in CLAUDE.md and
  `.env.example`; use it if environment-gating is adopted.

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
M
