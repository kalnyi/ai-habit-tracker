---
name: Project: habit CRUD and completion PBIs (PBI-001 through PBI-011)
description: Decisions and open questions recorded during authoring of the habit CRUD and habit completion PBIs
type: project
---

Six PBIs (001-006) created on 2026-04-16 covering the Habit entity CRUD feature.
Four PBIs (007-011) created on 2026-04-17 covering OpenAPI/Swagger and HabitCompletion CRUD.

**Why:** Core feature set for the Habit Tracker app (Layer 2). Backend REST only,
single user, no auth, Scala 2.13 + PostgreSQL + Akka HTTP.

**Resolved decisions (as of 2026-04-17):**
- Framework: Akka HTTP (observed in code — HabitRoutes uses akka.http.scaladsl).
- Migration tool: Liquibase (not Flyway — observed in infra/db/changelog/).
  PBI-001 still references `./gradlew flywayMigrate`; future PBIs use
  `./gradlew liquibaseUpdate`.
- Bare JSON array is used for GET /habits (observed in codebase — no envelope).

**Decisions in PBI-008 through PBI-011 (completions feature):**
- `habit_completions.completed_on` is DATE (not TIMESTAMPTZ) — day-level concept.
- Unique constraint on `(habit_id, completed_on)` — one completion per habit per day.
- Foreign key to `habits(id)` with no ON DELETE CASCADE — completion history preserved
  when habit is soft-deleted.
- No `updated_at` on completions — records are immutable; delete if wrong.
- Completions use hard delete (not soft delete) — no historical/analytical value
  once declared erroneous.
- Duplicate completion on same day returns 409 Conflict (not silent upsert).
- `from`/`to` date filter on list endpoint included in PBI-010 (needed by RAG later).
- Ownership check on DELETE completions enforced at DB layer (WHERE habit_id = ?).
- `note` field maps to free-text RAG embedding source for daily-tip and pattern-analysis.

**Open questions / pending engineer decisions (as of 2026-04-17):**
- Response envelope for GET /habits: bare array vs `{ "data": [] }` wrapper
  (still unconfirmed but bare array observed in code).
- Idempotency of habit DELETE: 404 vs 204 on already-deleted resource
  (PBI-006 specifies 404; engineer may override).
- Whether `from`/`to` filter on GET completions is in scope for PBI-010 or
  deferred (engineer decision requested in PBI-010 notes).
- Whether the Architect needs a new ADR for completions schema or extends
  the existing schema ADR (noted in PBI-008).

**How to apply:** When writing future PBIs that touch completions or Habit entity,
reference these decisions. Always use Liquibase terminology (not Flyway) for migration
steps. Check whether open questions above have been resolved before drafting PBIs
that depend on them.
