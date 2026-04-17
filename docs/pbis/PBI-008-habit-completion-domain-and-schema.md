# PBI-008: Habit Completion Domain Model and Database Schema

## User story
As a developer, I want a well-defined `HabitCompletion` domain model and a
versioned database schema, so that all subsequent habit completion operations
have a stable, consistent foundation to build on.

## Acceptance criteria
- [ ] A Liquibase changeset file exists at
      `infra/db/changelog/changesets/002-create-habit-completions-table.sql`
      and is referenced from `infra/db/changelog/db.changelog-master.xml`.
- [ ] The changeset creates a `habit_completions` table with at minimum the
      following columns:
      `id` (UUID, primary key),
      `habit_id` (UUID, NOT NULL, foreign key referencing `habits(id)`),
      `completed_on` (DATE, NOT NULL — the calendar date the habit was completed,
      stored as a date rather than a timestamp because a completion is a
      day-level fact, not a time-level event),
      `note` (TEXT, nullable — free-text annotation the user may attach),
      `created_at` (TIMESTAMPTZ, NOT NULL).
- [ ] A unique constraint or unique index is created on `(habit_id, completed_on)`
      so that a habit cannot be marked as completed more than once on the same
      calendar date.
- [ ] A partial index on `habit_id` (or `(habit_id, completed_on DESC)`) exists
      to support efficient querying of completions for a given habit.
- [ ] A Scala `case class HabitCompletion` exists in the `domain/` package with
      fields: `id: UUID`, `habitId: UUID`, `completedOn: java.time.LocalDate`,
      `note: Option[String]`, `createdAt: java.time.Instant`; all fields have
      explicit types.
- [ ] The migration runs successfully against a local PostgreSQL instance via
      `./gradlew liquibaseUpdate` with no errors.
- [ ] `./gradlew test` passes (no compilation errors or test failures introduced).

## Out of scope
- Any REST endpoints (covered in PBI-009 and PBI-010).
- Querying or listing completions (covered in PBI-010).
- Cascade behaviour on habit soft-delete — the foreign key does NOT use
  `ON DELETE CASCADE`; completion records are retained when a habit is
  soft-deleted (the `habits.deleted_at` mechanism already handles visibility).
- pgvector embedding columns (introduced when LLM features are built).
- Multi-user or per-user data partitioning.
- Any frontend work.

## Notes
- Depends on PBI-001 (the `habits` table must exist before this migration runs).
- The `completed_on` column is typed `DATE` (not `TIMESTAMPTZ`) intentionally.
  Habit completion is a day-level concept; recording a precise timestamp would
  introduce ambiguity around timezone handling. The Architect should confirm
  this decision in the schema ADR or a note.
- The foreign key references `habits(id)` without `ON DELETE CASCADE` — a
  completion record belonging to a soft-deleted habit is still valid historical
  data and must be preserved.
- The unique constraint on `(habit_id, completed_on)` enforces one-completion-
  per-day semantics at the database level, not only at the application layer.
- No `updated_at` column is included: a completion record is immutable once
  created. If a user records a completion in error they can delete it (PBI-011),
  but they cannot edit it.
- **ADR note:** No new ADR is required for this schema if the Architect treats
  the `DATE` vs `TIMESTAMPTZ` and foreign key decisions as extensions of the
  existing schema ADR. If those decisions are considered materially new, the
  Architect should append to the schema ADR or create a child ADR.

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
S
