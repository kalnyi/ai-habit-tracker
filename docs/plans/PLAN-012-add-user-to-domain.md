# PLAN-012: Add User to Domain

## PBI reference
PBI-012: Add User to Domain

## Summary
Introduce a `users` table with a single seeded row (`id=1, name='default'`),
add a `user_id BIGINT NOT NULL` column to `habits` with an FK to `users(id)`,
and wire `userId: Long` through the HTTP → service → repository chain as the
first path segment (`/users/{userId}/habits/...`). Every `HabitRepository`
method gains a `userId` parameter and appends `AND user_id = ?` to its SQL
(per ADR-007). Two new Liquibase changesets (003, 004) handle the schema
change and the default seed; the test suite is updated for path strings and
`userId` wiring only — assertion intent does not change.

## Preconditions / notes to the Developer agent

- **Build tool is Gradle**, not sbt. The PBI and Phase 1 brief reference
  `sbt test`; the actual command in this repo is `./gradlew test`.
  Use Gradle throughout. No migration to sbt is in scope.
- **ADR-007 is required reading** before touching any file in this plan.
  Four decisions are locked there: `users.id = BIGINT`, `habits.id` stays
  UUID, seed lives in Liquibase changeset 004 (not Docker Compose init),
  ownership is enforced at the DB layer via `WHERE user_id = ?`.
- **No authentication, sessions, or tokens.** `userId` in the URL is trusted
  unconditionally. No middleware is added in this PBI.
- **No new external dependencies.** Everything needed
  (`LongVar`, `Meta[Long]`, Liquibase, Doobie) is already on the classpath.
- **habits.id stays UUID** — do not touch that column, its codec, or the
  `UUIDVar` extractor.
- The one authoritative ownership-mismatch response is **404 NotFound**, not
  403 Forbidden. Do not introduce a new error variant.

## Affected files

| File | Change type | Description |
|---|---|---|
| `infra/db/changelog/changesets/003-create-users-table.sql` | Create | New Liquibase formatted-SQL changeset: `CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL);` + rollback. |
| `infra/db/changelog/changesets/004-add-user-id-to-habits.sql` | Create | Seeds default user (`id=1`), adds `user_id BIGINT` to `habits`, backfills `user_id = 1`, sets `NOT NULL`, adds FK + supporting index. |
| `infra/db/changelog/db.changelog-master.xml` | Modify | Append two `<include>` lines for changesets 003 and 004, preserving existing 001 / 002 entries. |
| `backend/src/main/scala/com/habittracker/domain/User.scala` | Create | New `final case class User(id: Long, name: String)`. |
| `backend/src/main/scala/com/habittracker/domain/Habit.scala` | Modify | Add `userId: Long` field to the `Habit` case class (after `id`). |
| `backend/src/main/scala/com/habittracker/repository/UserRepository.scala` | Create | New trait with `def findById(id: Long): IO[Option[User]]`. |
| `backend/src/main/scala/com/habittracker/repository/DoobieUserRepository.scala` | Create | Doobie implementation of `UserRepository`. |
| `backend/src/main/scala/com/habittracker/repository/HabitRepository.scala` | Modify | Every method signature gains `userId: Long`. |
| `backend/src/main/scala/com/habittracker/repository/DoobieHabitRepository.scala` | Modify | Every SQL gains `user_id` in INSERT or `AND user_id = $userId` in WHERE. `habitRead` is extended to include `user_id`. |
| `backend/src/main/scala/com/habittracker/service/HabitService.scala` | Modify | Every method signature gains `userId: Long`; passed through to repo; `Habit` construction in `createHabit` populates `userId`. |
| `backend/src/main/scala/com/habittracker/service/HabitCompletionService.scala` | Modify | Every method signature gains `userId: Long`; passed to `habitRepo.findActiveById(..., userId)`. Completion repo calls are unchanged. |
| `backend/src/main/scala/com/habittracker/http/HabitRoutes.scala` | Modify | All five routes re-rooted to `Root / "users" / LongVar(userId) / "habits" / ...`; `userId` threaded through to the service. Invalid-UUID 400 catch-alls preserved with the new prefix. |
| `backend/src/main/scala/com/habittracker/http/HabitCompletionRoutes.scala` | Modify | All three routes re-rooted to `Root / "users" / LongVar(userId) / "habits" / UUIDVar(habitId) / "completions" / ...`; `userId` threaded through to the service. Invalid-UUID 400 catch-alls preserved with the new prefix. |
| `backend/src/main/scala/com/habittracker/AppResources.scala` | Modify | Wire in `DoobieUserRepository` (constructed, exposed via `AppResources` field for Phase 1 use, not used by the route graph directly in this PBI). |
| `backend/src/main/resources/openapi/openapi.yaml` | Modify | Replace `/habits/...` paths with `/users/{userId}/habits/...`; add `userId` path parameter (`type: integer`, `format: int64`) to every affected path item. |
| `backend/src/test/scala/com/habittracker/repository/InMemoryHabitRepository.scala` | Modify | Every method signature gains `userId: Long`; internal filter adds `h.userId == userId`. |
| `backend/src/test/scala/com/habittracker/service/HabitServiceSpec.scala` | Modify | All service calls pass `userId = 1L`; assertions unchanged. |
| `backend/src/test/scala/com/habittracker/service/HabitCompletionServiceSpec.scala` | Modify | All service calls pass `userId = 1L`; `makeHabit` sets `userId = 1L`; assertions unchanged. |
| `backend/src/test/scala/com/habittracker/http/HabitRoutesSpec.scala` | Modify | Paths become `/users/1/habits[/...]`; `FakeHabitService` methods gain `userId: Long` param. |
| `backend/src/test/scala/com/habittracker/http/HabitCompletionRoutesSpec.scala` | Modify | Paths become `/users/1/habits/.../completions[/...]`; `FakeHabitCompletionService` methods gain `userId: Long` param. |
| `backend/src/test/scala/com/habittracker/integration/HabitApiIntegrationSpec.scala` | Modify | All HTTP paths become `/users/1/habits[/...]`; no other logic changes. Liquibase bootstrap is unchanged (the new changesets are picked up automatically because integration tests resolve the same changelog directory). |
| `backend/src/test/scala/com/habittracker/integration/HabitCompletionApiIntegrationSpec.scala` | Modify | All HTTP paths become `/users/1/habits/.../completions[/...]`. `beforeEach` cleanup adds a `DELETE FROM habit_completions` followed by `DELETE FROM habits` (unchanged order); no need to clean `users` since the seed is idempotent. |
| `backend/src/test/scala/com/habittracker/repository/DoobieHabitRepositorySpec.scala` | Modify | Every repository call passes `userId = 1L`; seeded `Habit` fixtures set `userId = 1L`. |
| `backend/src/test/scala/com/habittracker/repository/DoobieHabitCompletionRepositorySpec.scala` | Inspect only | Touches only the completion repo, whose signature does not change. Update only if it creates `Habit` rows directly — in which case add `userId = 1L` to the fixtures. |

## New components

- **`com.habittracker.domain.User`** (file: `backend/src/main/scala/com/habittracker/domain/User.scala`)
  ```scala
  package com.habittracker.domain

  final case class User(id: Long, name: String)
  ```

- **`com.habittracker.repository.UserRepository`** (file: `backend/src/main/scala/com/habittracker/repository/UserRepository.scala`)
  ```scala
  package com.habittracker.repository

  import cats.effect.IO
  import com.habittracker.domain.User

  trait UserRepository {
    def findById(id: Long): IO[Option[User]]
  }
  ```

- **`com.habittracker.repository.DoobieUserRepository`** (file: `backend/src/main/scala/com/habittracker/repository/DoobieUserRepository.scala`)
  ```scala
  package com.habittracker.repository

  import cats.effect.IO
  import com.habittracker.domain.User
  import doobie._
  import doobie.implicits._

  final class DoobieUserRepository(transactor: Transactor[IO]) extends UserRepository {

    private implicit val userRead: Read[User] =
      Read[(Long, String)].map { case (id, name) => User(id, name) }

    private def findByIdQuery(id: Long): Query0[User] =
      sql"""
        SELECT id, name
        FROM users
        WHERE id = $id
      """.query[User]

    override def findById(id: Long): IO[Option[User]] =
      findByIdQuery(id).option.transact(transactor)
  }
  ```

No new routes or endpoints besides the path-prefix change to existing ones.
No new services. No new DTOs — user data is not returned in any response in
this PBI.

## API contract

No new endpoints. All existing endpoints gain a `/users/{userId}` prefix.
`{userId}` is a **path segment typed as integer (`int64`)**.

| Method | Old path | New path |
|---|---|---|
| GET    | `/habits` | `/users/{userId}/habits` |
| POST   | `/habits` | `/users/{userId}/habits` |
| GET    | `/habits/{habitId}` | `/users/{userId}/habits/{habitId}` |
| PUT    | `/habits/{habitId}` | `/users/{userId}/habits/{habitId}` |
| DELETE | `/habits/{habitId}` | `/users/{userId}/habits/{habitId}` |
| POST   | `/habits/{habitId}/completions` | `/users/{userId}/habits/{habitId}/completions` |
| GET    | `/habits/{habitId}/completions` | `/users/{userId}/habits/{habitId}/completions` |
| DELETE | `/habits/{habitId}/completions/{completionId}` | `/users/{userId}/habits/{habitId}/completions/{completionId}` |

Request and response bodies are unchanged. Status codes are unchanged. A habit
that exists but belongs to a different user returns **404 `NotFound`** from
every endpoint (not 403). A non-numeric `{userId}` returns **404** (http4s's
default — `LongVar` fails to match, routes fall through to `orNotFound`); no
explicit `BadRequest` branch is required for `{userId}`.

## Database changes

Two new Liquibase formatted-SQL changesets, registered in the master changelog.

### New file: `infra/db/changelog/changesets/003-create-users-table.sql`

```sql
--liquibase formatted sql

--changeset habit-tracker:003-create-users-table
--comment: Creates the users table for the Habit Tracker PoC. See ADR-007.

CREATE TABLE users (
    id    BIGINT        PRIMARY KEY,
    name  VARCHAR(255)  NOT NULL
);

--rollback DROP TABLE IF EXISTS users;
```

### New file: `infra/db/changelog/changesets/004-add-user-id-to-habits.sql`

Ordering inside the single changeset:

1. Insert the default user (idempotent via `ON CONFLICT`).
2. Add `user_id` column as nullable.
3. Backfill existing rows with `user_id = 1`.
4. Set `NOT NULL`.
5. Add FK to `users(id)` (no cascade).
6. Add `(user_id, created_at DESC)` index for future list-by-user queries.

```sql
--liquibase formatted sql

--changeset habit-tracker:004-add-user-id-to-habits
--comment: Seeds the default user and adds habits.user_id with FK to users(id). See ADR-007.

-- Default user seed: idempotent so re-running against an already-seeded database is a no-op.
INSERT INTO users (id, name) VALUES (1, 'default')
ON CONFLICT (id) DO NOTHING;

-- Add the column as nullable so existing rows can be backfilled.
ALTER TABLE habits ADD COLUMN user_id BIGINT;

-- Backfill every existing habit to the default user.
UPDATE habits SET user_id = 1 WHERE user_id IS NULL;

-- Now the column is fully populated; enforce NOT NULL.
ALTER TABLE habits ALTER COLUMN user_id SET NOT NULL;

-- Foreign key without cascade (matches ADR-005's FK style).
ALTER TABLE habits
    ADD CONSTRAINT fk_habits_user
    FOREIGN KEY (user_id) REFERENCES users (id);

-- Index to support listing a user's active habits ordered by creation time.
CREATE INDEX idx_habits_user_created_at
    ON habits (user_id, created_at DESC)
    WHERE deleted_at IS NULL;

--rollback DROP INDEX IF EXISTS idx_habits_user_created_at;
--rollback ALTER TABLE habits DROP CONSTRAINT IF EXISTS fk_habits_user;
--rollback ALTER TABLE habits DROP COLUMN IF EXISTS user_id;
--rollback DELETE FROM users WHERE id = 1;
```

Note: the changeset carries two DML statements and four DDL statements. In
Liquibase formatted-SQL, each `--changeset` wraps its statements in a single
transaction by default — the `INSERT` and the subsequent `UPDATE ... SET
user_id = 1` therefore succeed or fail together, which is what we want.

### Modified file: `infra/db/changelog/db.changelog-master.xml`

Append two lines after the existing `002-create-habit-completions-table.sql`
entry:

```xml
<include file="changesets/003-create-users-table.sql" relativeToChangelogFile="true"/>
<include file="changesets/004-add-user-id-to-habits.sql" relativeToChangelogFile="true"/>
```

Do not touch the existing `001` and `002` lines.

## Migration changesets

| Changeset file | Registered in master | Purpose |
|---|---|---|
| `infra/db/changelog/changesets/003-create-users-table.sql` | yes | Create `users` table. |
| `infra/db/changelog/changesets/004-add-user-id-to-habits.sql` | yes | Seed user 1, add `habits.user_id` with FK and index, backfill existing rows. |

The exact SQL for both is given in the Database changes section above.

## Implementation details — backend

### `Habit` domain model (`domain/Habit.scala`)

Add `userId: Long` as the second field (right after `id`). Update every
construction site (only two in main code: `DefaultHabitService.createHabit`
and `DoobieHabitRepository.habitRead`). All test fixtures that build a `Habit`
directly must also be updated.

```scala
final case class Habit(
    id: UUID,
    userId: Long,
    name: String,
    description: Option[String],
    frequency: Frequency,
    createdAt: Instant,
    updatedAt: Instant,
    deletedAt: Option[Instant]
)
```

### `HabitRepository` trait (`repository/HabitRepository.scala`)

```scala
trait HabitRepository {
  def create(habit: Habit): IO[Unit]
  def listActive(userId: Long): IO[List[Habit]]
  def findActiveById(userId: Long, id: UUID): IO[Option[Habit]]
  def updateActive(
      userId: Long,
      id: UUID,
      name: String,
      description: Option[String],
      frequency: Frequency,
      updatedAt: Instant
  ): IO[Option[Habit]]
  def softDelete(userId: Long, id: UUID, at: Instant): IO[Boolean]
}
```

Rationale for parameter order: `userId` first, then the habit id. This makes
"I'm asking within the scope of a user" visually obvious at every call site.

`create` keeps a single-arg signature — `userId` is already on the `Habit`
case class, and splitting it out would duplicate information. The insert SQL
reads `user_id` off the passed `Habit`.

### `DoobieHabitRepository`

- `habitRead` becomes `Read[(UUID, Long, String, Option[String], Frequency,
  Instant, Instant, Option[Instant])]` mapped to `Habit`.
- `insertQuery` adds `user_id` to the column list and `${h.userId}` to the
  `VALUES`.
- Every `SELECT` and `UPDATE` adds `AND user_id = $userId` before
  `AND deleted_at IS NULL`.
- Example:
  ```scala
  private def findActiveByIdQuery(userId: Long, id: UUID): Query0[Habit] =
    sql"""
      SELECT id, user_id, name, description, frequency, created_at, updated_at, deleted_at
      FROM habits
      WHERE id = $id AND user_id = $userId AND deleted_at IS NULL
    """.query[Habit]
  ```
- `listActiveQuery` becomes a method `listActiveQuery(userId: Long)` with
  `WHERE user_id = $userId AND deleted_at IS NULL`.
- `softDeleteQuery(userId, id, at)` adds `AND user_id = $userId` to the WHERE.

### `HabitService` trait and `DefaultHabitService`

Every method signature gains `userId: Long` as the first parameter:

```scala
trait HabitService {
  def createHabit(userId: Long, req: CreateHabitRequest): IO[Either[AppError, HabitResponse]]
  def listHabits(userId: Long): IO[Either[AppError, List[HabitResponse]]]
  def getHabit(userId: Long, id: UUID): IO[Either[AppError, HabitResponse]]
  def updateHabit(userId: Long, id: UUID, req: UpdateHabitRequest): IO[Either[AppError, HabitResponse]]
  def deleteHabit(userId: Long, id: UUID): IO[Either[AppError, Unit]]
}
```

In `DefaultHabitService.createHabit`, the `Habit` construction populates
`userId = userId`. In all other methods, `userId` is simply forwarded to the
repository.

### `HabitCompletionService` trait and `DefaultHabitCompletionService`

Every method signature gains `userId: Long`:

```scala
trait HabitCompletionService {
  def recordCompletion(userId: Long, habitId: UUID, req: CreateHabitCompletionRequest): IO[Either[AppError, HabitCompletionResponse]]
  def listCompletions(userId: Long, habitId: UUID, from: Option[LocalDate], to: Option[LocalDate]): IO[Either[AppError, List[HabitCompletionResponse]]]
  def deleteCompletion(userId: Long, habitId: UUID, completionId: UUID): IO[Either[AppError, Unit]]
}
```

Implementation: replace `habitRepo.findActiveById(habitId)` with
`habitRepo.findActiveById(userId, habitId)`. `None` continues to map to
`NotFound(s"Habit '$habitId' not found")`. This is the single point where
ownership mismatch collapses into the existing 404 path, exactly as PBI-012
AC-7 requires. **Do not** introduce a separate "belongs to other user" error —
the DB-layer filter makes the case indistinguishable from "habit does not
exist at all", and that is intentional.

The `HabitCompletionRepository` interface is **not** changed. Completions
reach user scope transitively through the habit ownership check.

### `HabitRoutes` (`http/HabitRoutes.scala`)

Re-root every pattern under `"users" / LongVar(userId) / "habits"`. Keep the
`UUIDVar` extractor untouched. Example:

```scala
val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

  case GET -> Root / "users" / LongVar(userId) / "habits" =>
    service.listHabits(userId).flatMap {
      case Right(list) => Ok(list)
      case Left(err)   => ErrorHandler.toResponse(err)
    }

  case GET -> Root / "users" / LongVar(userId) / "habits" / UUIDVar(id) =>
    service.getHabit(userId, id).flatMap {
      case Right(habit) => Ok(habit)
      case Left(err)    => ErrorHandler.toResponse(err)
    }

  case GET -> Root / "users" / LongVar(_) / "habits" / _ =>
    BadRequest(ErrorResponse("Invalid habit id: must be a valid UUID"))

  case req @ POST -> Root / "users" / LongVar(userId) / "habits" =>
    req.as[CreateHabitRequest].flatMap { body =>
      service.createHabit(userId, body).flatMap {
        case Right(habit) => Created(habit)
        case Left(err)    => ErrorHandler.toResponse(err)
      }
    }.handleErrorWith { case _: DecodeFailure =>
      BadRequest(ErrorResponse("Malformed request body"))
    }

  case req @ PUT -> Root / "users" / LongVar(userId) / "habits" / UUIDVar(id) =>
    req.as[UpdateHabitRequest].flatMap { body =>
      service.updateHabit(userId, id, body).flatMap {
        case Right(habit) => Ok(habit)
        case Left(err)    => ErrorHandler.toResponse(err)
      }
    }.handleErrorWith { case _: DecodeFailure =>
      BadRequest(ErrorResponse("Malformed request body"))
    }

  case PUT -> Root / "users" / LongVar(_) / "habits" / _ =>
    BadRequest(ErrorResponse("Invalid habit id: must be a valid UUID"))

  case DELETE -> Root / "users" / LongVar(userId) / "habits" / UUIDVar(id) =>
    service.deleteHabit(userId, id).flatMap {
      case Right(_)  => NoContent()
      case Left(err) => ErrorHandler.toResponse(err)
    }

  case DELETE -> Root / "users" / LongVar(_) / "habits" / _ =>
    BadRequest(ErrorResponse("Invalid habit id: must be a valid UUID"))
}
```

Notes:
- Non-numeric `{userId}` (`/users/abc/habits`) does not match any case and
  falls through to 404 via `orNotFound`. That is acceptable per the PBI —
  `{userId}` is not in the existing "must return 400" contract; only the
  habit/completion UUID segments are.
- The invalid-UUID 400 catch-alls still pattern on `UUIDVar(_)` at the
  `userId` position so they only fire when the userId itself is valid.

### `HabitCompletionRoutes` (`http/HabitCompletionRoutes.scala`)

Same transformation. Every pattern gets `"users" / LongVar(userId)` prepended,
and every service call passes `userId` first. Keep the `FromParam` / `ToParam`
decoders and the `parseDate` helper untouched.

Example for the POST:

```scala
case req @ POST -> Root / "users" / LongVar(userId) / "habits" / UUIDVar(habitId) / "completions" =>
  req.as[CreateHabitCompletionRequest].flatMap { body =>
    service.recordCompletion(userId, habitId, body).flatMap {
      case Right(resp) => Created(resp)
      case Left(err)   => ErrorHandler.toResponse(err)
    }
  }.handleErrorWith { case _: DecodeFailure =>
    BadRequest(ErrorResponse("Malformed request body"))
  }
```

And the invalid-habitId catch-all:

```scala
case POST -> Root / "users" / LongVar(_) / "habits" / _ / "completions" =>
  BadRequest(ErrorResponse("Invalid habit id: must be a valid UUID"))
```

Apply the same shape to GET, GET with query params, and DELETE.

## LLM integration

None. This PBI does not touch LLM, RAG, embeddings, or prompts. Phase 1 will
consume the `userId: Long` signature introduced here; no further work is
required from this PBI.

## AppResources wiring

Current file (`backend/src/main/scala/com/habittracker/AppResources.scala`)
adds exactly one new repository construction. The route graph does not need
to include any user routes (there are none in scope), but the
`DoobieUserRepository` is constructed so Phase 1 and later phases can pull it
out of `AppResources` without a separate wiring change.

```scala
package com.habittracker

import cats.effect.{Clock, IO, Resource}
import cats.syntax.semigroupk._
import com.habittracker.http.{DocsRoutes, HabitCompletionRoutes, HabitRoutes}
import com.habittracker.repository.{
  DoobieHabitCompletionRepository,
  DoobieHabitRepository,
  DoobieUserRepository,
  UserRepository
}
import com.habittracker.service.{DefaultHabitCompletionService, DefaultHabitService}
import org.http4s.HttpRoutes

final case class AppResources(
    routes: HttpRoutes[IO],
    userRepo: UserRepository
)

object AppResources {

  def make: Resource[IO, AppResources] =
    for {
      xa             <- DatabaseConfig.transactor
      userRepo        = new DoobieUserRepository(xa)
      habitRepo       = new DoobieHabitRepository(xa)
      completionRepo  = new DoobieHabitCompletionRepository(xa)
      habitService    = new DefaultHabitService(habitRepo, Clock[IO])
      completionSvc   = new DefaultHabitCompletionService(habitRepo, completionRepo, Clock[IO])
      allRoutes       = new DocsRoutes().routes <+>
                        new HabitRoutes(habitService).routes <+>
                        new HabitCompletionRoutes(completionSvc).routes
    } yield AppResources(allRoutes, userRepo)
}
```

Check `com.habittracker.Main` — it currently destructures `AppResources(routes)`.
If it pattern-matches (e.g. `.use { case AppResources(routes) => ... }`),
update the match to `case AppResources(routes, _) => ...`. If it uses field
access (`.routes`), no change is needed.

## OpenAPI changes

File: `backend/src/main/resources/openapi/openapi.yaml`.

1. Remove the existing `/habits`, `/habits/{id}`, `/habits/{habitId}/completions`,
   and `/habits/{habitId}/completions/{completionId}` top-level path items.
2. Add a `userId` path parameter definition once (either inline on each path or
   as a component parameter; inline is fine here). Every affected path gets a
   `parameters:` entry at the path-item level:
   ```yaml
   - name: userId
     in: path
     required: true
     schema:
       type: integer
       format: int64
   ```
3. Add new paths with the `/users/{userId}/...` prefix, preserving the
   existing request bodies, response bodies, and status-code documentation
   verbatim:

```yaml
paths:
  /users/{userId}/habits:
    parameters:
      - name: userId
        in: path
        required: true
        schema: { type: integer, format: int64 }
    post: { ... existing CreateHabit body ... }
    get:  { ... existing ListHabits body ... }

  /users/{userId}/habits/{habitId}:
    parameters:
      - name: userId
        in: path
        required: true
        schema: { type: integer, format: int64 }
      - name: habitId
        in: path
        required: true
        schema: { type: string, format: uuid }
    get: { ... }
    put: { ... }
    delete: { ... }

  /users/{userId}/habits/{habitId}/completions:
    parameters:
      - name: userId
        in: path
        required: true
        schema: { type: integer, format: int64 }
      - name: habitId
        in: path
        required: true
        schema: { type: string, format: uuid }
    post: { ... }
    get:  { ... }

  /users/{userId}/habits/{habitId}/completions/{completionId}:
    parameters:
      - name: userId
        in: path
        required: true
        schema: { type: integer, format: int64 }
      - name: habitId
        in: path
        required: true
        schema: { type: string, format: uuid }
      - name: completionId
        in: path
        required: true
        schema: { type: string, format: uuid }
    delete: { ... }
```

4. The existing path-item-level parameter for `habitId` (e.g. the `/habits/{id}`
   block uses `name: id`) becomes `name: habitId` in the new layout; verify the
   operation descriptions still read correctly.
5. Do not change `components.schemas`. No DTO shape changes.

## Test plan

All existing test intent is preserved. The only things that change are (a)
path strings, (b) `userId = 1L` wiring in service / repo / fake calls, and
(c) `Habit` fixtures that now set `userId = 1L`.

### `InMemoryHabitRepository` (`backend/src/test/scala/com/habittracker/repository/InMemoryHabitRepository.scala`)

- Every method gains `userId: Long` as the first parameter.
- `listActive(userId)` filters: `store.values.filter(h => h.userId == userId && h.deletedAt.isEmpty)`.
- `findActiveById(userId, id)` filters: `store.get(id).filter(h => h.userId == userId && h.deletedAt.isEmpty)`.
- `updateActive(userId, id, ...)`: the `.get(id).filter(...)` check adds `h.userId == userId`.
- `softDelete(userId, id, at)`: same — `.get(id).filter(h => h.userId == userId && h.deletedAt.isEmpty)`.
- `create(habit)` is unchanged structurally (the habit already carries
  `userId`), but reviewers should verify the test inserts `Habit` values with
  `userId = 1L`.

### `HabitServiceSpec`

- All `svc.createHabit(req)` calls become `svc.createHabit(1L, req)`.
- Same for `listHabits`, `getHabit`, `updateHabit`, `deleteHabit`.
- The existing "create a habit" test must additionally assert that the
  returned habit is retrievable via `getHabit(1L, habit.id)` (which it
  already does via `listHabits`; no new assertion required).
- Add **one new test**:
  `"getHabit returns NotFound when the habit belongs to a different user"`.
  Build a repo pre-seeded with a habit whose `userId = 2L`, then call
  `svc.getHabit(1L, habit.id)` and assert `NotFound`. Do the same for
  `updateHabit` and `deleteHabit` in at least one representative case each.

### `HabitCompletionServiceSpec`

- `makeHabit` helper sets `userId = 1L` on the returned `Habit`.
- All `svc.recordCompletion(habitId, req)` calls become
  `svc.recordCompletion(1L, habitId, req)`; same pattern for `listCompletions`
  and `deleteCompletion`.
- Add **one new test**:
  `"recordCompletion returns NotFound when the habit belongs to a different user"`.
  Seed `habitRepo` with a habit whose `userId = 2L`, call
  `svc.recordCompletion(1L, habit.id, req)`, assert `NotFound`. Same for
  `listCompletions` and `deleteCompletion` in one representative case each.

### `HabitRoutesSpec`

- `FakeHabitService` method signatures gain `userId: Long`.
- All `Uri`s become `/users/1/habits[...]`. Examples:
  - `uri"/habits"` → `uri"/users/1/habits"`
  - `Uri.unsafeFromString(s"/habits/$fixedId")` → `Uri.unsafeFromString(s"/users/1/habits/$fixedId")`
  - `uri"/habits/not-a-uuid"` → `uri"/users/1/habits/not-a-uuid"`
- No assertion changes.

### `HabitCompletionRoutesSpec`

- `FakeHabitCompletionService` method signatures gain `userId: Long`.
- All `Uri`s become `/users/1/habits/{habitId}/completions[...]`.
- No assertion changes.

### `HabitApiIntegrationSpec`

- All HTTP paths become `/users/1/habits[...]`. The Liquibase bootstrap block
  is unchanged — changesets 003 and 004 run automatically.
- `beforeEach` is unchanged: `DELETE FROM habits` is safe because the FK on
  `habit_completions.habit_id` has no cascade (ADR-005). The `users` row is
  not deleted between tests — it is seeded once by Liquibase and stays put.
- All assertions unchanged.

### `HabitCompletionApiIntegrationSpec`

- All HTTP paths become `/users/1/habits/{habitId}/completions[...]`.
- `beforeEach`: `DELETE FROM habit_completions` then `DELETE FROM habits`,
  unchanged. Do not `DELETE FROM users`.
- All assertions unchanged.

### `DoobieHabitRepositorySpec`

- Every method call under test passes `userId = 1L`.
- Every `Habit` fixture constructs with `userId = 1L`.
- Add one new test: `findActiveById(userId = 2L, habit.id)` returns `None`
  when the habit was inserted with `userId = 1L`. This is the explicit
  "DB-layer ownership filter works" assertion.

### `DoobieHabitCompletionRepositorySpec`

- The completion repo itself is unchanged. Inspect the spec: if it constructs
  `Habit` rows directly to satisfy the FK, update those fixtures to set
  `userId = 1L`. Nothing else changes.

### New acceptance-level assertions to add to integration suite

Add to `HabitApiIntegrationSpec`:

- **AC-3 verification**: after Liquibase bootstrap, a single SQL query
  `SELECT COUNT(*) FROM users WHERE id = 1 AND name = 'default'` returns 1.
  This can live in a one-off test inside the existing spec or as a new
  small `UserSchemaSpec`; prefer inlining into `HabitApiIntegrationSpec` to
  avoid another Testcontainers boot.
- **AC-5 / AC-6 verification (cross-user isolation)**: create a habit via
  `POST /users/1/habits`. Then `GET /users/2/habits/{id}` → assert 404 (or
  whatever status the `orNotFound` fallback produces; if the expected status
  is 404, do not special-case it). A second `POST /users/2/habits` then
  `GET /users/1/habits` must not include the user-2 habit. Keep these as a
  single "cross-user isolation" test.

### Commands to run
- `./gradlew compile` after each file group (domain → repo → service → routes).
- `./gradlew test` once all files compile.
- No tests are expected to be `@Ignore`d or skipped. Integration tests stay
  marked `@Ignore` per the existing convention (they require Docker).

## ADRs required

- **ADR-007-user-domain.md** — written as part of this plan. Documents the
  four decisions called out in PBI-012 Technical Note 7 (user id type, habit
  id type resolution, seed placement, ownership enforcement approach).

## Open questions

None blocking. Two minor points for the engineer's awareness:

1. **Non-numeric `{userId}` behaviour**: http4s's `LongVar` fails to match and
   the route falls through to `orNotFound` (404). The PBI does not require
   400 for this case (only the UUID segments require 400). This plan accepts
   404. If a 400 is preferred later, a wildcard catch-all `case _ / "users" /
   _ / "habits" / _ => BadRequest(...)` could be added — noted but not
   implemented.
2. **`users.id` generation strategy**: none in this PBI (seed is a hardcoded
   literal). Adding `GENERATED BY DEFAULT AS IDENTITY` will be a new changeset
   when user creation is first implemented; deferring it now is explicit in
   ADR-007.

---

This technical plan is ready for your review. Please approve or request changes before I hand off to the Developer agent.
