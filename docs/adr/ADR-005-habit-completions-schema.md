# ADR-005: Habit completions schema — date-level completion, hard delete, DB-enforced uniqueness, FK without cascade

## Status
Accepted

## Context
PBIs 008 through 011 introduce recording, listing, and deleting per-day habit
completions. Four schema-level decisions needed an explicit resolution before
implementation could start; all four extend (rather than contradict) ADR-002
(the habits schema) but are material enough to deserve their own record rather
than being buried in a PBI note.

1. **`completed_on` granularity — DATE vs TIMESTAMPTZ.** A completion is a
   user-declared fact about a calendar day ("I meditated yesterday"), not a
   time-stamped event. Storing a timestamp would introduce two costs: a
   timezone-interpretation question ("is `2026-04-17T23:59:59Z` today or
   tomorrow for a user in UTC+10?") and a false level of precision. Storing a
   DATE sidesteps both.

2. **Delete semantics — hard vs soft.** `habits` use `deleted_at`-based soft
   delete (ADR-002) because history is analytically valuable. Completions are
   different: a completion the user chose to delete is *by definition* a
   completion they claim never happened. Keeping a tombstone row would pollute
   streak and pattern-analysis queries with ambiguous "is this still real?"
   entries, and there is no audit requirement in the PoC that demands retention.

3. **One-completion-per-day enforcement — DB constraint vs service check only.**
   The PBIs require that a duplicate completion on the same `(habit_id, completed_on)`
   returns HTTP 409. The enforcement point can be (a) a race-prone service-layer
   check, (b) a DB unique constraint that surfaces as an integrity violation,
   or (c) both. A DB-level constraint is cheap, eliminates the time-of-check /
   time-of-use race, and produces a single authoritative source of truth.

4. **Foreign key to `habits` — with or without `ON DELETE CASCADE`.** Because
   `habits` use soft delete, a `DELETE` on `habits` never actually runs — so
   `ON DELETE CASCADE` would fire only in exceptional situations (manual
   cleanup, test harness). However, it would silently remove completion rows
   in those situations, which contradicts the "preserve history" intent of
   soft delete. A plain FK with no cascade enforces referential integrity
   against invented habit IDs while leaving historical completion data
   undisturbed.

## Decision

### 1. `completed_on` is typed `DATE NOT NULL`
- Column: `completed_on DATE NOT NULL`.
- Mapped in Scala to `java.time.LocalDate` (not `Instant`, not `OffsetDateTime`).
- Doobie's `doobie-postgres` module supplies a native `Meta[LocalDate]` via
  `doobie.postgres.implicits._` — no custom `Meta` needed.
- The HTTP boundary accepts and returns ISO 8601 `YYYY-MM-DD` strings (e.g.
  `"2026-04-17"`). A Circe codec maps `String` ↔ `LocalDate` using
  `LocalDate.parse(...)`.
- `created_at TIMESTAMPTZ NOT NULL` is kept separately as a server-side audit
  field (when the record was inserted, not which day it represents).

### 2. Completions use hard delete
- `DELETE /habits/{habitId}/completions/{completionId}` issues a literal SQL
  `DELETE FROM habit_completions WHERE id = ? AND habit_id = ?`.
- There is no `deleted_at` column on `habit_completions`.
- The ownership check (completion must belong to the given habit) is part of
  the `WHERE` clause — no extra round-trip.
- Rationale: the user's intent when deleting a completion is to assert it did
  not happen. Retaining the row would poison streak and RAG queries with
  ambiguous data. If a future feature needs an undelete workflow, it can
  introduce a separate `completion_deletions` audit table rather than
  retrofitting soft delete.

### 3. Uniqueness is enforced by a DB UNIQUE constraint *and* a service-layer pre-check
- Constraint:
  ```sql
  CONSTRAINT uq_habit_completions_habit_day UNIQUE (habit_id, completed_on)
  ```
  declared inline on the table (produces a supporting unique index
  automatically; no separate `CREATE INDEX` needed).
- The service layer calls `repo.findByHabitAndDate(habitId, completedOn)`
  *before* insert and returns `AppError.ConflictError` when the record exists.
  This turns the common case into a clean 409 without relying on SQL state
  code parsing.
- A DB `SQLIntegrityConstraintViolation` (raised only under a narrow race
  between the lookup and the insert, or if a duplicate is inserted by a tool
  other than the service) is caught in `DoobieHabitCompletionRepository.create`
  using Doobie's `sqlstate.class23.UNIQUE_VIOLATION` selector and translated
  into a typed `Left(ConflictError(...))` returned from the repository. The
  service passes this through unchanged.
- Rationale: the service check gives a clean error message and avoids the
  "check by exception" anti-pattern in the normal case; the DB constraint
  guarantees correctness under concurrency and protects against direct SQL
  writes. Both layers return the same `ConflictError` variant, so the
  HTTP mapping is uniform.

### 4. Foreign key is declared WITHOUT `ON DELETE CASCADE`
- FK clause:
  ```sql
  CONSTRAINT fk_habit_completions_habit
    FOREIGN KEY (habit_id) REFERENCES habits (id)
  ```
  (no `ON DELETE` action — defaults to `NO ACTION`).
- The application never issues a real `DELETE FROM habits`, so the cascade
  behaviour is an edge case, but we choose the safer default.
- Soft-deleted habits still satisfy the FK (the row exists in `habits` with
  a non-null `deleted_at`) — completion records for a soft-deleted habit
  remain valid historical rows.
- The read path for completions (`GET /habits/{habitId}/completions`) first
  checks `habit_exists_and_active(habitId)` at the service layer — a habit
  that has been soft-deleted returns 404 from the API even though the
  completion rows physically remain.

### 5. Index strategy
- The UNIQUE constraint on `(habit_id, completed_on)` produces an index that
  supports "find today's completion" lookups.
- An additional index
  ```sql
  CREATE INDEX idx_habit_completions_habit_day_desc
    ON habit_completions (habit_id, completed_on DESC);
  ```
  supports the `GET /habits/{habitId}/completions` list query, which orders
  by `completed_on DESC`. The uniqueness index is in the opposite order and
  will not be used for range scans; the explicit DESC index is worth the
  storage cost.
- No index on `created_at` — not queried in any PBI.

### 6. `note` is `TEXT NULL`
- Column: `note TEXT` (nullable).
- No length limit. The note is the seed for the RAG embedding pipeline and
  should not be artificially truncated at the schema layer. Length policy, if
  any, is an application-layer decision deferred until embedding rules are
  defined.

## Consequences

**Easier:**
- Timezone handling becomes a non-problem: the server records exactly what
  date the user specified, with no implicit conversion.
- Duplicate detection is free under concurrency — no DB-level race to reason
  about.
- Hard delete means downstream streak and analytics queries can treat
  `habit_completions` as an append-mostly fact table with no filtering
  predicates beyond the date range.
- Reusing ADR-002's `updated_at`-style pattern is unnecessary because
  completions are immutable; the model is simpler than `habits`.

**Harder / trade-offs:**
- Users in two timezones sharing a calendar cannot be reconciled if we ever
  add multi-user support — the server stores a naked date. Acceptable for the
  PoC; a future multi-user ADR will need to add a client-specified timezone or
  store an `OffsetDateTime` alongside the `DATE`.
- An accidental hard delete is permanent. The deletion endpoint is the only
  write path that can cause data loss; reviewers must treat it as such.
- The service-layer pre-check duplicates logic that the DB constraint already
  enforces. Acceptable because the pre-check exists to produce a clean error
  payload, not to guarantee correctness.
- A `SQLIntegrityConstraintViolation` raised *in the happy path* (e.g. under a
  race) escapes the service layer as a Doobie exception unless the repository
  catches it. The repository is therefore required to use Doobie's
  `sqlstate.class23.UNIQUE_VIOLATION` combinator and translate the exception
  into `Left(ConflictError)`. Forgetting this step is a silent bug that will
  surface only under load — reviewers must check for it in PR-009.

**Locked in:**
- Hard delete is the only removal mechanism for completions. Any future
  retention requirement must introduce a separate audit mechanism, not reverse
  this decision.
- `completed_on` is date-level. Any future requirement for time-of-day
  tracking must introduce a new column (e.g. `completed_at TIMESTAMPTZ`) and
  accept that it is distinct from `completed_on`.
- The FK between `habit_completions.habit_id` and `habits.id` does not cascade.
- One completion per habit per day is a hard invariant, enforced at both the
  service and DB layers.
