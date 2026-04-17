# PBI-001: Habit Domain Model and Database Schema

## User story
As a developer, I want a well-defined Habit domain model and a versioned database
schema, so that all subsequent habit CRUD operations have a stable, consistent
foundation to build on.

## Acceptance criteria
- [ ] A Flyway migration file exists at `infra/db/migrations/V1__create_habits_table.sql`
      that creates a `habits` table with at minimum the following columns:
      `id` (UUID, primary key), `name` (VARCHAR NOT NULL), `description` (TEXT, nullable),
      `frequency` (VARCHAR NOT NULL — values `daily` or `weekly`; extensible to custom
      values in future without schema change), `deleted_at` (TIMESTAMPTZ, nullable —
      NULL means active, non-NULL means soft-deleted), `created_at` (TIMESTAMPTZ NOT NULL),
      `updated_at` (TIMESTAMPTZ NOT NULL).
- [ ] A Scala `case class Habit` exists in the `domain/` package with fields that
      mirror the schema above; all fields have explicit types.
- [ ] A `Frequency` ADT (sealed trait or enum-like structure) is defined in the
      `domain/` package with at least `Daily` and `Weekly` cases, plus a fallback
      `Custom(value: String)` case to accommodate future extensions without breaking
      changes.
- [ ] The migration runs successfully against a local PostgreSQL instance via
      `./gradlew flywayMigrate` with no errors.
- [ ] `./gradlew test` passes (no compilation errors or test failures introduced).

## Out of scope
- Any REST endpoints (those are covered in PBI-002 through PBI-006).
- Multi-user or per-user data partitioning.
- pgvector columns (those are introduced when LLM features are built).
- Any frontend work.

## Notes
- **ADR required (blocker):** The Architect agent must produce two ADRs before
  implementation begins:
  1. Framework ADR — choosing between http4s and Play Framework. This affects
     project dependencies, effect system wiring, and test approach.
  2. Schema ADR — documenting the `frequency` extensibility strategy (VARCHAR vs
     enum type vs lookup table) and the soft-delete approach (`deleted_at` column).
- `frequency` is stored as `VARCHAR` rather than a PostgreSQL `ENUM` type to allow
  future custom frequencies without requiring a migration that alters an enum.
  This decision must be confirmed in the schema ADR.
- `id` should be a UUID generated at the application layer, not a serial integer,
  to make the API stable and future-proof for potential multi-node deployments.
- `updated_at` must be kept current on every write; consider a PostgreSQL trigger or
  explicit application-layer update (Architect to decide and document).

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
S
