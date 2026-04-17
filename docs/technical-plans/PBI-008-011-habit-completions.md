# PLAN: Habit Completions (PBI-008 through PBI-011)

## PBI reference
- PBI-008: Habit Completion Domain Model and Database Schema
- PBI-009: Record a Habit Completion
- PBI-010: List Completions for a Habit
- PBI-011: Delete a Habit Completion

## Summary
Introduce a new `habit_completions` table (Liquibase changeset 002), a
`HabitCompletion` domain case class, a Doobie-backed repository, a service that
enforces habit existence and duplicate-day semantics, and three new Akka HTTP
endpoints nested under `/habits/{habitId}/completions`. The design follows the
existing Habit stack patterns one-for-one — `Repository` trait + `DoobieX`
implementation, `Service` trait + `DefaultX` implementation, `Routes` class
mixing `JsonSupport`, DTOs under `http/dto/`, Circe codecs under
`http/CompletionCodecs.scala`, and the same `AppError → Route` error mapping.
The one new schema decision (date-level `completed_on`, hard delete, DB-level
unique constraint, FK without cascade) is recorded in ADR-005. A new
`AppError.ConflictError` variant is added and wired into `ErrorHandler`. No new
libraries or frameworks are introduced.

## Affected files

| File | Change type | Description |
|------|-------------|-------------|
| `infra/db/changelog/changesets/002-create-habit-completions-table.sql` | Create | Liquibase formatted-SQL changeset that creates the `habit_completions` table, the unique constraint on `(habit_id, completed_on)`, the supporting list index, and the FK to `habits(id)`. Includes `--rollback` lines. |
| `infra/db/changelog/db.changelog-master.xml` | Modify | Add one `<include file="changesets/002-create-habit-completions-table.sql" relativeToChangelogFile="true"/>` line after the existing 001 include. |
| `backend/src/main/scala/com/habittracker/domain/HabitCompletion.scala` | Create | `final case class HabitCompletion(id: UUID, habitId: UUID, completedOn: LocalDate, note: Option[String], createdAt: Instant)`. Mirrors the `Habit` case class style. |
| `backend/src/main/scala/com/habittracker/domain/AppError.scala` | Modify | Add a new case: `final case class ConflictError(message: String) extends AppError`. No changes to existing variants. |
| `backend/src/main/scala/com/habittracker/repository/HabitCompletionRepository.scala` | Create | New trait with five methods (see "New components" below). |
| `backend/src/main/scala/com/habittracker/repository/DoobieHabitCompletionRepository.scala` | Create | Doobie implementation. Mirrors `DoobieHabitRepository` style: `Read[HabitCompletion]` instance, explicit `sql"..."` fragments, `.transact(transactor)`. |
| `backend/src/main/scala/com/habittracker/service/HabitCompletionService.scala` | Create | New trait + `DefaultHabitCompletionService` class. Takes both `HabitRepository` (to check habit existence) and `HabitCompletionRepository` as constructor parameters. |
| `backend/src/main/scala/com/habittracker/http/dto/CreateHabitCompletionRequest.scala` | Create | `final case class CreateHabitCompletionRequest(completedOn: LocalDate, note: Option[String])`. |
| `backend/src/main/scala/com/habittracker/http/dto/HabitCompletionResponse.scala` | Create | DTO with `id`, `habitId`, `completedOn`, `note`, `createdAt` fields and a `fromHabitCompletion` factory. |
| `backend/src/main/scala/com/habittracker/http/CompletionCodecs.scala` | Create | Circe codecs for `LocalDate`, `HabitCompletionResponse`, and `CreateHabitCompletionRequest`. `LocalDate` codec uses `LocalDate.parse(_)` / `_.toString` (both produce `YYYY-MM-DD`). |
| `backend/src/main/scala/com/habittracker/http/HabitCompletionRoutes.scala` | Create | New Akka HTTP `Route` class exposing the three endpoints nested under `/habits/{habitId}/completions`. |
| `backend/src/main/scala/com/habittracker/http/ErrorHandler.scala` | Modify | Add a `case ConflictError(msg) => complete(StatusCodes.Conflict, ErrorResponse(msg))` branch to `toRoute`. |
| `backend/src/main/scala/com/habittracker/Main.scala` | Modify | Instantiate `DoobieHabitCompletionRepository`, `DefaultHabitCompletionService`, and `HabitCompletionRoutes`; concatenate its route with the existing ones using `~`. |
| `backend/src/main/resources/openapi/openapi.yaml` | Modify | Add three path operations, the `CreateHabitCompletionRequest` and `HabitCompletionResponse` schemas, and document all response codes (201 / 204 / 400 / 404 / 409 as appropriate). |
| `backend/src/test/scala/com/habittracker/repository/DoobieHabitCompletionRepositorySpec.scala` | Create | Testcontainers-backed repository test covering create, findByHabitAndDate, listByHabit (with date filters), deleteByIdAndHabit, and the unique-violation translation to `ConflictError`. |
| `backend/src/test/scala/com/habittracker/service/HabitCompletionServiceSpec.scala` | Create | Fast in-memory unit tests using an `InMemoryHabitCompletionRepository` fake and the existing `InMemoryHabitRepository` fake (lifted from `HabitServiceSpec`). |
| `backend/src/test/scala/com/habittracker/http/HabitCompletionRoutesSpec.scala` | Create | `ScalatestRouteTest` unit test with a `FakeHabitCompletionService` exercising every HTTP response code. |
| `backend/src/test/scala/com/habittracker/http/HabitCompletionCodecsSpec.scala` | Create | Round-trip tests for the new codecs (`LocalDate`, request DTO, response DTO). |
| `backend/src/test/scala/com/habittracker/integration/HabitCompletionApiIntegrationSpec.scala` | Create | New integration spec (Testcontainers + real Liquibase) wiring the full route tree. Marked `@Ignore` like the existing integration spec. Covers every acceptance criterion across PBI-009, 010, 011. |

No existing migration file is edited. No files are deleted.

## New components

### `com.habittracker.domain.HabitCompletion`
```
final case class HabitCompletion(
    id: UUID,
    habitId: UUID,
    completedOn: LocalDate,
    note: Option[String],
    createdAt: Instant
)
```

### `com.habittracker.domain.AppError.ConflictError`
New sealed-hierarchy variant: `final case class ConflictError(message: String) extends AppError`.
Used exclusively for "duplicate completion on same `(habit_id, completed_on)`"
today; the name is generic so that future conflict-style 409s can reuse it.

### `com.habittracker.repository.HabitCompletionRepository` (trait)
```
trait HabitCompletionRepository {
  def create(completion: HabitCompletion): IO[Either[ConflictError, Unit]]
  def findByHabitAndDate(habitId: UUID, completedOn: LocalDate): IO[Option[HabitCompletion]]
  def listByHabit(habitId: UUID, from: Option[LocalDate], to: Option[LocalDate]): IO[List[HabitCompletion]]
  def deleteByIdAndHabit(completionId: UUID, habitId: UUID): IO[Boolean]
}
```
- `create` returns `Left(ConflictError)` when the DB unique constraint trips
  (using Doobie's `sqlstate.class23.UNIQUE_VIOLATION` combinator). The service
  layer still performs a pre-check to cover the common case with a clean
  message; the DB-level check is the concurrent-safe backstop.
- `listByHabit` applies the date-range filter directly in SQL (`WHERE` clauses
  built conditionally) and orders by `completed_on DESC`.
- `deleteByIdAndHabit` enforces ownership in a single SQL statement and
  returns `true` iff exactly one row was deleted.

### `com.habittracker.repository.DoobieHabitCompletionRepository`
Doobie implementation. Package/path: `backend/src/main/scala/com/habittracker/repository/DoobieHabitCompletionRepository.scala`.
Imports: `doobie._`, `doobie.implicits._`, `doobie.postgres.implicits._`
(brings in `Meta[LocalDate]` and `Meta[Instant]`). One SQL fragment per
repository method. `create` catches `UNIQUE_VIOLATION` via
`.exceptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION => IO.pure(Left(ConflictError(...))) }`
(or equivalent pattern — the Developer agent chooses the precise Doobie API
call that fits the 1.0.0-RC5 surface).

### `com.habittracker.service.HabitCompletionService` (trait)
```
trait HabitCompletionService {
  def recordCompletion(
      habitId: UUID,
      req: CreateHabitCompletionRequest
  ): IO[Either[AppError, HabitCompletionResponse]]

  def listCompletions(
      habitId: UUID,
      from: Option[LocalDate],
      to: Option[LocalDate]
  ): IO[Either[AppError, List[HabitCompletionResponse]]]

  def deleteCompletion(
      habitId: UUID,
      completionId: UUID
  ): IO[Either[AppError, Unit]]
}
```

### `com.habittracker.service.DefaultHabitCompletionService`
Constructor: `(habitRepo: HabitRepository, completionRepo: HabitCompletionRepository, clock: Clock[IO])`.

- `recordCompletion`:
  1. `habitRepo.findActiveById(habitId)` — if `None`, return `Left(NotFound(...))`.
  2. `completionRepo.findByHabitAndDate(habitId, req.completedOn)` — if
     `Some(_)`, return `Left(ConflictError("Habit '{habitId}' already has a completion for {completedOn}"))`.
  3. Generate a new `UUID`, get `now` from `clock.realTimeInstant`, build a
     `HabitCompletion`, call `completionRepo.create(...)`.
  4. If `create` returns `Left(ConflictError)` (race path), pass it through.
  5. On success, return `Right(HabitCompletionResponse.fromHabitCompletion(...))`.

- `listCompletions`:
  1. `habitRepo.findActiveById(habitId)` — if `None`, return `Left(NotFound(...))`.
  2. `completionRepo.listByHabit(habitId, from, to)` — wrap in `Right`.
  3. The `from` / `to` parameters are already `Option[LocalDate]` — parsing
     happens at the route layer so the service can stay ignorant of HTTP-level
     concerns.

- `deleteCompletion`:
  1. `habitRepo.findActiveById(habitId)` — if `None`, return `Left(NotFound("Habit '{habitId}' not found"))`.
  2. `completionRepo.deleteByIdAndHabit(completionId, habitId)` — if `false`,
     return `Left(NotFound("Completion '{completionId}' not found"))`.
  3. On `true`, return `Right(())`.

### `com.habittracker.http.HabitCompletionRoutes`
Akka HTTP `Route` class in `backend/src/main/scala/com/habittracker/http/HabitCompletionRoutes.scala`.
Constructor: `(service: HabitCompletionService)(implicit runtime: IORuntime)`.
Mixes in `JsonSupport`.

Route tree (mounted under `/habits/{habitId}/completions`):
```
pathPrefix("habits") {
  // Invalid habitId (non-UUID) is caught before descending into completions.
  concat(
    pathPrefix(JavaUUID) { habitId =>
      pathPrefix("completions") {
        concat(
          pathEndOrSingleSlash {
            concat(
              (post & entity(as[CreateHabitCompletionRequest])) { ... 201 / 400 / 404 / 409 ... },
              (get & parameters("from".as[String].optional, "to".as[String].optional)) { (from, to) =>
                // parse LocalDate, return 400 on parse failure
                ... 200 / 400 / 404 ...
              }
            )
          },
          path(JavaUUID) { completionId =>
            delete { ... 204 / 404 ... }
          },
          // Non-UUID completionId
          path(Segment) { _ =>
            complete(StatusCodes.BadRequest, ErrorResponse("Invalid completion id: must be a valid UUID"))
          }
        )
      }
    },
    // Non-UUID habitId — produce 400 for /habits/not-a-uuid/completions too.
    pathPrefix(Segment) { _ =>
      pathPrefix("completions") {
        complete(StatusCodes.BadRequest, ErrorResponse("Invalid habit id: must be a valid UUID"))
      }
    }
  )
}
```
(The precise tree shape is left to the Developer agent; the above expresses
the match semantics the endpoints must satisfy.)

**IMPORTANT integration point with `HabitRoutes`:** the existing `HabitRoutes`
already owns `pathPrefix("habits")`. The two options for the Developer agent
are:

1. **Split ownership, concatenate routes.** `HabitCompletionRoutes` owns its
   own `pathPrefix("habits") { pathPrefix(JavaUUID) { pathPrefix("completions") { ... } } }`
   subtree. The two routes are combined in `Main.scala` with `habitRoutes.route ~ habitCompletionRoutes.route`.
   Akka HTTP will try each in order; whichever one does not match falls through
   to the next.
2. **Merge into `HabitRoutes`.** Extend the existing class to take a
   `HabitCompletionService` and nest the completion routes inside the
   `pathPrefix("habits")` tree.

**Decision: option 1 (split ownership).** Rationale: keeps `HabitRoutes`
unchanged (no regression risk in PBI-002..006 endpoints), and each route class
stays focused on a single domain. The Developer agent must verify that the
existing `path(Segment)` fallback in `HabitRoutes` (which catches non-UUID
habit ids and returns 400) is ordered such that it still works — specifically,
the `HabitCompletionRoutes` subtree must be concatenated *before* that fallback,
or the completion routes must also include their own non-UUID fallback (shown
in the sketch above). The Developer agent should pick whichever keeps the
existing `HabitRoutesSpec` tests green.

## API contract

### POST /habits/{habitId}/completions
- **Path parameter:** `habitId` — UUID.
- **Request body (JSON):**
  ```
  {
    "completedOn": "2026-04-17",
    "note": "Felt energised"
  }
  ```
  - `completedOn`: ISO 8601 `YYYY-MM-DD` date. Required.
  - `note`: string, optional, may be `null` or omitted.
- **Response 201 Created:**
  ```
  {
    "id": "6f9619ff-8b86-d011-b42d-00c04fc964ff",
    "habitId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "completedOn": "2026-04-17",
    "note": "Felt energised",
    "createdAt": "2026-04-17T10:30:00Z"
  }
  ```
- **Response 400 Bad Request:** non-UUID `habitId`; missing or unparseable
  `completedOn`; malformed JSON body.
- **Response 404 Not Found:** `habitId` does not exist or is soft-deleted.
- **Response 409 Conflict:** a completion already exists for the
  `(habitId, completedOn)` pair.
- All 4xx responses use the existing `ErrorResponse` envelope
  (`{ "message": "..." }`).

### GET /habits/{habitId}/completions
- **Path parameter:** `habitId` — UUID.
- **Query parameters:**
  - `from` (optional) — ISO 8601 `YYYY-MM-DD`. Filters to completions on or
    after this date.
  - `to` (optional) — ISO 8601 `YYYY-MM-DD`. Filters to completions on or
    before this date.
- **Response 200 OK:** JSON array of `HabitCompletionResponse` objects,
  ordered by `completedOn` descending. Empty array if the habit has no
  completions (or none match the filter).
- **Response 400 Bad Request:** non-UUID `habitId`; `from` or `to` cannot
  be parsed as `LocalDate`.
- **Response 404 Not Found:** `habitId` does not exist or is soft-deleted.

### DELETE /habits/{habitId}/completions/{completionId}
- **Path parameters:** `habitId` and `completionId` — both UUIDs.
- **Request body:** none.
- **Response 204 No Content:** deletion succeeded; no body.
- **Response 400 Bad Request:** either path parameter is not a valid UUID.
- **Response 404 Not Found:** the habit does not exist (or is soft-deleted);
  OR the completion does not exist; OR the completion exists but is owned by
  a different habit. The response body uses the existing `ErrorResponse`
  envelope with a message distinguishing "habit not found" from
  "completion not found" where possible, but the status code is the same.

## Database changes

New Liquibase changeset: `infra/db/changelog/changesets/002-create-habit-completions-table.sql`.

```sql
--liquibase formatted sql

--changeset habit-tracker:002-create-habit-completions-table
--comment: Creates the habit_completions table for the Habit Tracker PoC. See ADR-005.

CREATE TABLE habit_completions (
    id            UUID         PRIMARY KEY,
    habit_id      UUID         NOT NULL,
    completed_on  DATE         NOT NULL,
    note          TEXT,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_habit_completions_habit_day UNIQUE (habit_id, completed_on),
    CONSTRAINT fk_habit_completions_habit
        FOREIGN KEY (habit_id) REFERENCES habits (id)
);

CREATE INDEX idx_habit_completions_habit_day_desc
    ON habit_completions (habit_id, completed_on DESC);

--rollback DROP INDEX IF EXISTS idx_habit_completions_habit_day_desc;
--rollback DROP TABLE IF EXISTS habit_completions;
```

Update to `infra/db/changelog/db.changelog-master.xml` — add one line inside
`<databaseChangeLog>`:

```xml
<include file="changesets/002-create-habit-completions-table.sql" relativeToChangelogFile="true"/>
```

**No existing migration file is modified.** The changeset is additive.

## LLM integration
This PBI batch does not introduce any LLM calls. The schema is deliberately
designed with RAG in mind (the `note` field is uncapped TEXT; `listByHabit`
accepts an arbitrary date range that matches what the daily-tip retrieval
window will need), but no prompt files, no embedding calls, and no Anthropic
API touches are added in this batch.

## Test plan

### Unit tests (no Docker required)

1. **`HabitCompletionCodecsSpec`** — round-trip tests:
   - `LocalDate` encode/decode for `"2026-04-17"`.
   - `CreateHabitCompletionRequest` with and without `note`.
   - `HabitCompletionResponse` round-trip.
   - `LocalDate` decode failure on `"not-a-date"` produces a `Left` with a
     non-empty message.

2. **`HabitCompletionServiceSpec`** (using `AsyncIOSpec` like
   `HabitServiceSpec`):
   - `recordCompletion` returns `NotFound` when the habit does not exist.
   - `recordCompletion` returns `NotFound` when the habit is soft-deleted.
   - `recordCompletion` returns `ConflictError` when a duplicate exists for
     the same `(habitId, completedOn)` (service pre-check path).
   - `recordCompletion` happy path — returns `Right(response)` with all
     fields populated; `id` non-null, `createdAt` non-null, `note` propagated.
   - `recordCompletion` happy path without `note` — `note` is `None`.
   - `listCompletions` returns `NotFound` for missing habit.
   - `listCompletions` returns an empty list for a habit with no completions.
   - `listCompletions` applies `from` and `to` filters correctly.
   - `listCompletions` returns results ordered by `completedOn` DESC.
   - `deleteCompletion` returns `NotFound` for missing habit.
   - `deleteCompletion` returns `NotFound` when the completion does not exist.
   - `deleteCompletion` returns `NotFound` when the completion belongs to a
     different habit (ownership check).
   - `deleteCompletion` happy path — returns `Right(())` and a subsequent
     `listCompletions` no longer contains the deleted row.

3. **`HabitCompletionRoutesSpec`** (using `ScalatestRouteTest` and a
   `FakeHabitCompletionService`, mirroring `HabitRoutesSpec`):
   - POST 201 + correct JSON body on happy path.
   - POST 400 on non-UUID `habitId`.
   - POST 400 on malformed JSON body.
   - POST 400 on unparseable `completedOn`.
   - POST 404 when service returns `NotFound`.
   - POST 409 when service returns `ConflictError`.
   - GET 200 with array on happy path.
   - GET 200 with `[]` when service returns empty list.
   - GET 400 on unparseable `from` query param.
   - GET 400 on unparseable `to` query param.
   - GET 400 on non-UUID `habitId`.
   - GET 404 when service returns `NotFound`.
   - DELETE 204 on success.
   - DELETE 400 on non-UUID `habitId`.
   - DELETE 400 on non-UUID `completionId`.
   - DELETE 404 when service returns `NotFound`.
   - That existing `HabitRoutesSpec` tests still pass unchanged.

4. **`HabitCodecsSpec`** (existing file, no modification expected unless the
   Developer finds a test that was implicitly testing the closed-world set of
   `AppError` cases — in which case a failing unit test should be added for
   `ConflictError` serialisation through `ErrorHandler`).

### Repository / integration tests (require Docker Testcontainers)

Both are marked `@Ignore` — consistent with existing
`DoobieHabitRepositorySpec` / `HabitApiIntegrationSpec`. They are run manually.

5. **`DoobieHabitCompletionRepositorySpec`**:
   - `create` persists a completion and returns `Right(())`.
   - `create` returns `Left(ConflictError(...))` when a duplicate
     `(habit_id, completed_on)` is inserted (verifies the `UNIQUE_VIOLATION`
     translation).
   - `findByHabitAndDate` returns `Some` for a matching record, `None` otherwise.
   - `listByHabit` with no filters returns all completions for the habit in
     `completed_on DESC` order.
   - `listByHabit` with `from` and `to` filters returns only in-range records.
   - `deleteByIdAndHabit` returns `true` and removes the row on a real match.
   - `deleteByIdAndHabit` returns `false` when the `completionId` does not
     exist.
   - `deleteByIdAndHabit` returns `false` when the completion exists but
     under a different `habit_id` (ownership check at the SQL layer).
   - A direct SQL query after `deleteByIdAndHabit` confirms the row is *gone*
     (hard delete, not soft).
   - FK integrity: inserting a completion with a `habit_id` that does not
     exist in `habits` raises a foreign-key violation.

6. **`HabitCompletionApiIntegrationSpec`** (Testcontainers + Akka HTTP server,
   mirroring `HabitApiIntegrationSpec`):
   - **PBI-009 AC**: creates a habit via `POST /habits`, POSTs a valid
     completion, asserts 201 with all expected response fields.
   - **PBI-009 AC**: POST for a non-existent `habitId` → 404.
   - **PBI-009 AC**: POST twice for the same `(habitId, completedOn)` — first
     201, second 409.
   - **PBI-009 AC**: POST with invalid `completedOn` string → 400.
   - **PBI-009 AC**: POST with non-UUID `habitId` → 400.
   - **PBI-010 AC**: creates multiple completions on different dates, GETs
     the list, asserts they are ordered by `completedOn` DESC.
   - **PBI-010 AC**: GET with `from` and `to` query params — only in-range
     records returned.
   - **PBI-010 AC**: GET for a non-existent habit → 404.
   - **PBI-010 AC**: GET for a habit with no completions → 200 with body `[]`.
   - **PBI-010 AC**: GET with `from=not-a-date` → 400.
   - **PBI-011 AC**: creates a habit and completion, DELETEs the completion
     → 204, subsequent GET confirms the row is absent.
   - **PBI-011 AC**: DELETE with a non-existent `completionId` → 404.
   - **PBI-011 AC**: DELETE with a `completionId` that belongs to a different
     `habitId` → 404 (ownership check).
   - **PBI-008 AC**: direct SQL query confirms the unique constraint exists
     (e.g. `INSERT ... ON CONFLICT DO NOTHING` behaviour verified at Doobie
     layer; equivalently, the `409` case from the API layer implicitly
     confirms it).
   - **PBI-008 AC**: `./gradlew update` applies the changeset against a
     fresh container with no errors (implicit — the test container runs
     Liquibase in `beforeAll`).

### Test discipline
- All new ScalaTest specs are annotated with
  `@RunWith(classOf[JUnitRunner])` (matches every existing spec).
- All tests that require Docker are annotated `@Ignore` (matches every
  existing Docker-dependent spec).
- The Developer agent must run `./gradlew test` at the end. `-PskipDockerTests`
  is acceptable for CI / first-pass verification.

## OpenAPI spec changes

Additions to `backend/src/main/resources/openapi/openapi.yaml`:

1. **Three new path operations** under a single new top-level path
   `/habits/{habitId}/completions` (and one under
   `/habits/{habitId}/completions/{completionId}`):
   - `POST /habits/{habitId}/completions` — operationId `recordHabitCompletion`.
   - `GET /habits/{habitId}/completions` — operationId `listHabitCompletions`.
   - `DELETE /habits/{habitId}/completions/{completionId}` — operationId
     `deleteHabitCompletion`.
   Each documents the full set of response codes listed in the API contract
   section above, each referencing `ErrorResponse` for 4xx cases.

2. **Two new schemas** under `components.schemas`:
   - `CreateHabitCompletionRequest`:
     ```yaml
     type: object
     required:
       - completedOn
     properties:
       completedOn:
         type: string
         format: date
       note:
         type: string
         nullable: true
     ```
   - `HabitCompletionResponse`:
     ```yaml
     type: object
     required:
       - id
       - habitId
       - completedOn
       - createdAt
     properties:
       id:
         type: string
         format: uuid
       habitId:
         type: string
         format: uuid
       completedOn:
         type: string
         format: date
       note:
         type: string
         nullable: true
       createdAt:
         type: string
         format: date-time
     ```

3. **No changes to the `Frequency`, `CreateHabitRequest`, `UpdateHabitRequest`,
   `HabitResponse`, or `ErrorResponse` schemas.**

## ADRs required
- `docs/adr/ADR-005-habit-completions-schema.md` — **created as part of this
  plan.** Covers the four schema-level decisions (date granularity, hard
  delete, DB-level uniqueness with service pre-check, FK without cascade).

No other ADRs are needed. The backend framework (ADR-001), the original
habit schema (ADR-002), the migration tool (ADR-003), and the OpenAPI
approach (ADR-004) all stand unchanged.

## New dependencies
**None.** The required `java.time.LocalDate` ↔ PostgreSQL `DATE` mapping is
supplied by `doobie-postgres` 1.0.0-RC5, which is already a declared
dependency. The Circe `LocalDate` codec is hand-written in
`CompletionCodecs.scala` (same pattern as the existing `Instant` codec in
`HabitCodecs`). No new libraries are added. No build changes other than the
source files themselves.

## Open questions

1. **Error message content for "completion belongs to a different habit".**
   PBI-011 mandates 404 for both "completion does not exist" and "completion
   exists but under a different habitId". Should the error messages differ
   ("Completion not found" vs "Completion does not belong to habit")? The
   plan currently assumes they may differ but the status code is the same.
   Please confirm the UX preference, or confirm either is acceptable.

2. **404 disambiguation: habit vs completion missing on DELETE.** When
   `DELETE /habits/{habitId}/completions/{completionId}` hits a missing
   habit, the plan returns `NotFound("Habit '{habitId}' not found")`. When
   the habit exists but the completion does not, the plan returns
   `NotFound("Completion '{completionId}' not found")`. Both are 404 with
   different messages. Please confirm this behaviour is acceptable, or
   request a uniform message.

3. **`HabitCompletionRoutes` placement in the Main route tree.** The plan
   recommends splitting completion routes into their own class and
   concatenating with `~` in `Main.scala`. If you prefer merging the
   completion routes directly into `HabitRoutes` (fewer moving parts, but a
   larger diff to an existing file), say so and I will revise the plan.

4. **Reuse of the `InMemoryHabitRepository` test fake.** The existing fake
   lives inside `HabitServiceSpec`. The new
   `HabitCompletionServiceSpec` needs the same fake. Two options: (a) copy
   the class (small, keeps specs independent), or (b) lift it into a new
   `src/test/scala/com/habittracker/repository/InMemoryHabitRepository.scala`
   shared helper. The plan assumes (a) unless you prefer (b).

This technical plan is ready for your review. Please approve or request changes before I hand off to the Developer agent.
