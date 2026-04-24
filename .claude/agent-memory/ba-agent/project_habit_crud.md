---
name: Project: habit CRUD, completion, user domain, and Phase 1 PBIs (PBI-001 through PBI-013)
description: Decisions and open questions recorded during authoring of habit CRUD, completions, user domain, and Phase 1 pattern detection PBIs
type: project
---

Six PBIs (001-006) created on 2026-04-16 covering the Habit entity CRUD feature.
Four PBIs (007-011) created on 2026-04-17 covering OpenAPI/Swagger and HabitCompletion CRUD.
PBI-012 created 2026-04-22 adding User to domain and scoping all endpoints to /users/{userId}/...
PBI-013 created 2026-04-22 covering Phase 1 pattern detection + LLM narrative endpoint.

**Why:** Core feature set for the Habit Tracker app (Layer 2). Backend REST only,
single user, no auth, Scala 2.13 + PostgreSQL. Framework migrated from Akka HTTP
to http4s (completed before Phase 1 began — confirmed by git commit e558cd3).

**Resolved decisions (as of 2026-04-17):**
- Migration tool: Liquibase (not Flyway — observed in infra/db/changelog/).
  PBI-001 still references `./gradlew flywayMigrate`; future PBIs use
  `./gradlew liquibaseUpdate`.
- Bare JSON array is used for GET /habits (observed in codebase — no envelope).

**Resolved decisions (as of 2026-04-22, PBI-012):**
- Framework: http4s 0.23.27 + Cats Effect 3 (Akka HTTP removed — commit e558cd3).
- habitId: UUID (not Long). habits.id stays UUID. All analytical queries use UUID.
- userId: Long (BIGINT in DB).
- All endpoints scoped to /users/{userId}/... prefix using LongVar extractor.
- Build tool: Gradle (`./gradlew test`), not sbt — phase brief says sbt but that is incorrect.

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

**Decisions in PBI-013 (Phase 1 pattern detection):**
- Single PBI — not decomposed; phase brief is the authoritative spec.
- Endpoint: GET /users/{userId}/habits/insights → 200 InsightResponse.
- AnthropicClient: Scala object with direct sttp call, no trait/abstract class.
- ANTHROPIC_API_KEY: from environment; clear startup error if missing.
- InsightPrompt.build: pure function (no F[_], no IO).
- SYSTEM_PROMPT: named constant on InsightPrompt object.
- LLM model: claude-sonnet-4-20250514 (hardcoded constant in AnthropicClient).
- No new external dependencies — sttp already on classpath.
- SQL tests use Testcontainers with real Postgres (Architect to confirm in ADR).
- Phase 1 files (Analytics.scala, AnthropicClient.scala, InsightPrompt.scala)
  are carry-over contracts for Phase 2 — must not be designed to block Phase 2 extension.
- ADR required before Developer agent proceeds (file structure, wiring, test approach).

**Open questions / pending engineer decisions (as of 2026-04-17):**
- Response envelope for GET /habits: bare array vs `{ "data": [] }` wrapper
  (still unconfirmed but bare array observed in code).
- Idempotency of habit DELETE: 404 vs 204 on already-deleted resource
  (PBI-006 specifies 404; engineer may override).
- Whether the Architect needs a new ADR for completions schema or extends
  the existing schema ADR (noted in PBI-008).

**How to apply:** When writing future PBIs that touch completions or Habit entity,
reference these decisions. Always use Liquibase terminology (not Flyway) for migration
steps. Always use Gradle (`./gradlew test`) not sbt. Phase 1 files are frozen
contracts — Phase 2 PBIs extend them, never redefine.
