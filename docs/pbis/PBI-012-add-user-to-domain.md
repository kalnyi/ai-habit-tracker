# PBI-012: Add User to Domain

## User story

As the system, I want habits to be owned by a named user so that every
API endpoint, SQL query, and future analytics function can scope data to
a specific user, unblocking Phase 1 (pattern detection) and all subsequent
phases that assume `userId` in their contracts.

---

## Acceptance criteria

- [ ] **AC-1 — users table.**
  A Liquibase changeset creates a `users` table with `id BIGINT PRIMARY KEY`
  and `name VARCHAR(255) NOT NULL`. The table is registered in
  `db.changelog-master.xml`. Rollback SQL is included in the changeset.

- [ ] **AC-2 — default user seed.**
  A Docker Compose init SQL file (or a Liquibase data changeset) inserts
  one row: `(id=1, name='default')`. This row exists in the database after
  running `docker compose up` with a fresh volume. The seed runs only once
  (idempotent or guarded by `ON CONFLICT DO NOTHING`).

- [ ] **AC-3 — habits.user_id foreign key.**
  A Liquibase changeset adds a `user_id BIGINT NOT NULL` column to the
  `habits` table with a foreign key referencing `users(id)`. All existing
  habit rows receive `user_id = 1` as part of the same changeset (UPDATE
  before NOT NULL constraint is enforced). Rollback SQL is included.

- [ ] **AC-4 — User domain model.**
  A `User` case class exists in `com.habittracker.domain` with at minimum
  `id: Long` and `name: String`. A `UserRepository` trait exists in
  `com.habittracker.repository` with at minimum:
  ```scala
  def findById(id: Long): IO[Option[User]]
  ```
  A Doobie implementation (`DoobieUserRepository`) is wired into
  `AppResources`.

- [ ] **AC-5 — HabitRepository filters by userId.**
  Every method on `HabitRepository` that returns one or more habits accepts
  a `userId: Long` parameter and adds `WHERE user_id = ?` to its SQL.
  The `create` method assigns the provided `userId` to the new row.
  No habit data crosses user boundaries.

- [ ] **AC-6 — Habit CRUD endpoints scoped to user.**
  All five habit CRUD endpoints are moved to the new paths:
  ```
  GET    /users/{userId}/habits
  POST   /users/{userId}/habits
  GET    /users/{userId}/habits/{habitId}
  PUT    /users/{userId}/habits/{habitId}
  DELETE /users/{userId}/habits/{habitId}
  ```
  `{userId}` is a `Long` path segment. `{habitId}` retains its existing
  type (Architect to confirm — see Technical Notes). The service/repository
  layer receives `userId` from the path, not from a request body or header.

- [ ] **AC-7 — Habit completion endpoints scoped to user.**
  Both completion endpoints are moved to the new paths:
  ```
  GET    /users/{userId}/habits/{habitId}/completions
  POST   /users/{userId}/habits/{habitId}/completions
  ```
  The service verifies habit ownership (habit must belong to `userId`) before
  recording or listing completions. Ownership mismatch returns 404 (habit not
  found for that user), not 403.

- [ ] **AC-8 — habitId type: UUID retained, Phase 1 brief amended.**
  `habits.id` remains `UUID`. The Phase 1 brief (`phase_1_pattern_detection.md`)
  has been amended to use `UUID` for `habitId` throughout
  (`streakForHabit(habitId: UUID)`, `HabitContext.streaks: Map[UUID, Int]`).
  No schema change to `habits.id` is required by this PBI.

- [ ] **AC-9 — OpenAPI spec updated.**
  All paths in `backend/src/main/resources/openapi/openapi.yaml` are updated
  to reflect the new `/users/{userId}/...` prefix. The `userId` path parameter
  is documented as `integer` (int64 format) on every affected path.

- [ ] **AC-10 — Regression: all existing tests pass.**
  `sbt test` passes with zero failures. Tests that reference old
  paths (e.g. `/habits`, `/habits/{id}`) are updated to use the new
  `/users/1/habits/...` paths. Test assertions (status codes, response
  bodies, repository behaviour) are unchanged in intent — only path
  strings and userId wiring are updated.

---

## Out of scope

- **Authentication, sessions, or tokens of any kind.** The `userId` in the
  path is trusted unconditionally. No middleware, no JWT, no session cookie.
- **User CRUD endpoints.** No `GET /users`, `POST /users`, `PUT /users/{id}`,
  or `DELETE /users/{id}`.
- **User lookup in responses.** Habit responses do not need to embed or
  expand user data — `user_id` in the DB is sufficient.
- **Multiple users or user creation flow.** Only the one seeded default user
  (id=1) is needed to unblock Phase 1.
- **Frontend changes.** No frontend work in this PBI.
- **pgvector, embeddings, or RAG.** Not touched here.
- **Phase 1 analytics or LLM calls.** Those are covered by the Phase 1 PBI.
- **Soft-delete or lifecycle management for users.** Users table is
  insert-only for now.
- **HabitCompletion DELETE endpoint** (`DELETE /users/{userId}/habits/{habitId}/completions/{completionId}`).
  This endpoint already exists at `/habits/{habitId}/completions/{completionId}`
  and must be updated to the new path prefix, but no new business logic is
  needed beyond the ownership check already applied via `habitId`.

---

## Technical notes for the Architect

### 1. habitId type — RESOLVED (engineer decision, 2026-04-22)

`habits.id` stays `UUID`. The Phase 1 brief has been amended to use `UUID`
for `habitId` (`streakForHabit(habitId: UUID)`, `HabitContext.streaks: Map[UUID, Int]`).
No schema migration of `habits.id` is required. The Architect's ADR should
note this decision for traceability.

### 2. Liquibase changeset ordering

New changeset numbers must follow the existing sequence:
- `003-create-users-table.sql`
- `004-add-user-id-to-habits.sql`

The UPDATE to set `user_id = 1` on existing rows must appear in changeset
004, **before** the NOT NULL constraint and FK are added (within the same
changeset, or via a precondition). See ADR-003 and existing changesets
for the Liquibase XML include pattern.

### 3. Default user seed placement

Two options for the `(id=1, name='default')` seed:
- **Liquibase data changeset** (preferred): keeps all schema + seed in one
  system, tracked in changelog history.
- **Docker Compose init SQL** in `infra/db/init/`: runs at container
  creation, outside Liquibase control.

The Architect must pick one and document it. If using a Liquibase changeset,
mark it with `runOnChange="false"` and `failOnError="true"`.

### 4. http4s route wiring

`LongVar` is available from `org.http4s.dsl.io._` — no new dependency
needed. Existing `UUIDVar` can be kept for `habitId` (if UUID is retained)
or replaced by `LongVar` (if migrated to Long). Both route classes
(`HabitRoutes`, `HabitCompletionRoutes`) need their root prefix updated
to `"users" / LongVar(userId) / "habits"`. The `AppResources` DI wiring
does not change structurally — just pass `userId` down through the route
→ service → repository call chain.

### 5. UserRepository scope

For this PBI, `UserRepository` only needs `findById`. The Developer agent
does not need to implement user validation on every request (this is not an
auth check) — the Phase 1 analytics queries use `userId: Long` directly in
SQL without a round-trip user lookup.

### 6. Test update strategy

The existing unit tests (`HabitRoutesSpec`, `HabitCompletionRoutesSpec`,
`HabitServiceSpec`, `HabitCompletionServiceSpec`) use an in-memory
`InMemoryHabitRepository`. That class will need a `userId` parameter on its
methods after AC-5. Update the in-memory impl to filter by `userId` using
the stored value.

Integration tests (`HabitApiIntegrationSpec`, `HabitCompletionApiIntegrationSpec`)
use Testcontainers with the real Liquibase changelog. After the migration
changesets are added, the integration tests will automatically run against
the updated schema. Only path strings and `userId` wiring need updating.

### 7. ADR required

This PBI introduces schema decisions (user table, FK strategy, ID type
resolution) that require an ADR. The ADR should cover:
- User ID type (BIGINT serial vs UUID)
- Habit ID type decision (resolution of the Long/UUID conflict above)
- Seed data strategy (Liquibase changeset vs init SQL)
- Ownership enforcement approach (DB-layer WHERE vs service-layer lookup)

---

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
M
