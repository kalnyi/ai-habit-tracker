# ADR-002: Habit table schema — frequency as VARCHAR, soft delete, application-managed updated_at

## Status
Accepted

## Context
PBI-001 introduces the `habits` table, the foundation for all habit CRUD
operations. Three schema-level decisions needed an explicit resolution before
implementation could start:

1. **`frequency` representation.** Today only `daily` and `weekly` are valid;
   later PBIs anticipate custom frequencies (e.g. `every-3-days`, user-defined
   cadences). The options were: PostgreSQL `ENUM` type, a lookup table with a
   foreign key, or a plain `VARCHAR` with application-layer validation.
2. **Soft delete representation.** PBI-006 mandates that deletes preserve the
   row for future analytics. Options were: a boolean `is_deleted` flag, a
   nullable `deleted_at TIMESTAMPTZ` column, or a separate "archive" table.
3. **`updated_at` maintenance.** The column must change on every successful
   write (PBI-005). Options were: a PostgreSQL `BEFORE UPDATE` trigger, a
   generated-column approach, or setting it explicitly in application code.

Engineer decisions already recorded (from the PBI handoff):
- `frequency` stored as VARCHAR.
- Soft delete via a `deleted_at TIMESTAMPTZ` column (NULL = active).

This ADR locks in those decisions and adds the `updated_at` strategy.

## Decision

### 1. `frequency` column — VARCHAR(32) NOT NULL, validated in the application
- Type: `VARCHAR(32) NOT NULL`. No `CHECK` constraint in the database.
- Accepted values are enforced by the Scala `Frequency` ADT:
  `Daily`, `Weekly`, `Custom(value: String)`.
- In this PBI batch the REST API only accepts `"daily"` and `"weekly"`; the
  ADT reserves `Custom(_)` so that future PBIs can accept additional values
  without a migration or DB change.
- Rationale: a DB `ENUM` would force a migration every time we add a value,
  and a lookup table is overkill for a PoC. `VARCHAR` plus strict
  application-layer validation gives us the flexibility the domain calls for
  without introducing schema churn.

### 2. Soft delete — `deleted_at TIMESTAMPTZ NULL`
- Column: `deleted_at TIMESTAMPTZ` with default `NULL`.
- Semantics: `NULL` = active; non-NULL = soft-deleted at the stored UTC instant.
- All read endpoints (`GET /habits`, `GET /habits/{id}`) filter with
  `WHERE deleted_at IS NULL`.
- `DELETE /habits/{id}` performs `UPDATE habits SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL`
  and uses the row count to decide between `204` and `404`.
- A partial index `idx_habits_active ON habits (id) WHERE deleted_at IS NULL`
  is added so that active-habit lookups stay fast as historical rows accumulate.
- Rationale: a single timestamp column carries more information than a boolean
  (when was it deleted, not just whether) at no extra cost, and keeps all
  history in one table — simpler for the forthcoming RAG embedding pipeline
  to reason about.

### 3. `updated_at` — maintained by the application, not the database
- Column: `updated_at TIMESTAMPTZ NOT NULL`.
- On INSERT, the application sets `created_at` and `updated_at` to the same
  `Instant.now()` value.
- On UPDATE (PUT handler and DELETE handler), the application sets
  `updated_at = Instant.now()` explicitly in the SQL statement.
- No `BEFORE UPDATE` trigger is installed.
- Rationale:
  - Keeps behaviour visible in Scala code; there is no hidden DB logic that
    tests and developers must remember.
  - Triggers complicate testing (rows updated from a test harness or migration
    would silently get a different `updated_at` than application writes).
  - The write surface area is small (two mutating endpoints), so the cost of
    the explicit approach is negligible.
  - Keeps the door open to later replace the clock with an injected
    `Clock[IO]` for deterministic time in tests.

## Consequences

**Easier:**
- Adding a new frequency value is a code-only change (add a case to
  `Frequency`, accept it in the request parser) — no DB migration.
- `deleted_at` doubles as an audit trail for when a habit was abandoned, which
  will be useful signal for pattern-analysis prompts later.
- `updated_at` logic is explicit and test-inspectable — no need to simulate
  trigger timing.

**Harder / trade-offs:**
- Data integrity of `frequency` values depends entirely on the application. A
  direct SQL write (e.g. a manual `psql` session) could insert an invalid
  value; we accept this for a single-writer PoC. If the system later grows
  to multiple writers or external ETL, adding a `CHECK (frequency IN (...))`
  constraint can be reconsidered.
- Forgetting to set `updated_at` on a new mutating endpoint is a silent bug.
  Mitigation: repository methods that mutate rows must always set
  `updated_at` as part of the SQL; reviewers check for this.
- The partial index on `deleted_at IS NULL` needs to be dropped and recreated
  if the `id` column type ever changes (not anticipated — UUID is stable).

**Locked in:**
- Soft delete is the only deletion mechanism. Hard deletes are prohibited by
  CLAUDE.md and PBI-006.
- `frequency` is a free-form string at the DB layer. Any future DB-level
  constraint must be introduced by a new migration, never by editing V1.
