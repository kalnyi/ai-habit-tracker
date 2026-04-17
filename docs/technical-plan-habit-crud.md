# Technical Plan: Habit CRUD REST Backend

## PBI references
- PBI-001: Habit Domain Model and Database Schema
- PBI-002: Create Habit
- PBI-003: List Habits
- PBI-004: Get Single Habit
- PBI-005: Update Habit
- PBI-006: Soft-Delete Habit

## Summary
Stand up a Scala 2.13 + Gradle backend using **Akka HTTP** (per the revised
ADR-001, following the engineer's framework override), Circe for JSON via
`akka-http-circe`, Doobie for PostgreSQL access, and Flyway for schema
migrations. Introduce a single `V1__create_habits_table.sql` migration
(per ADR-002) and expose six habit endpoints with a shared `ErrorResponse`
envelope, UUID-based identifiers, UTC timestamps, and soft-delete semantics.
The HTTP layer uses `Future`; the service and repository layers keep
`cats-effect` / `IO` per CLAUDE.md, with a narrow bridge at route handlers.
No authentication — single-user PoC.

## ADRs required
- `docs/adr/ADR-001-backend-framework.md` — **Akka HTTP** chosen via engineer
  override; http4s and Play rejected. This ADR has been revised to reflect the
  override and to document the `Future`/`IO` boundary rule.
- `docs/adr/ADR-002-habit-schema.md` — frequency VARCHAR, soft-delete via
  `deleted_at`, application-managed `updated_at`.

Both ADRs have been written alongside this plan.

---

## Gradle dependencies to add

Scala version: **2.13.14** (pin in `build.gradle` as `scalaVersion = '2.13.14'`).

Akka HTTP family version: **10.6.3** (latest stable in the 10.6.x line at time
of writing). Akka Streams version: **2.9.3** (the paired runtime for Akka HTTP
10.6.x per the Akka release matrix). The Developer agent must pin both
versions and must not mix with older 10.2.x / 2.6.x artifacts.

| Scope | Group | Artifact | Version |
|-------|-------|----------|---------|
| implementation | org.scala-lang | scala-library | 2.13.14 |
| implementation | com.typesafe.akka | akka-http_2.13 | 10.6.3 |
| implementation | com.typesafe.akka | akka-http-core_2.13 | 10.6.3 |
| implementation | com.typesafe.akka | akka-stream_2.13 | 2.9.3 |
| implementation | com.typesafe.akka | akka-actor-typed_2.13 | 2.9.3 |
| implementation | com.typesafe.akka | akka-slf4j_2.13 | 2.9.3 |
| implementation | de.heikoseeberger | akka-http-circe_2.13 | 1.39.2 |
| implementation | io.circe | circe-core_2.13 | 0.14.9 |
| implementation | io.circe | circe-generic_2.13 | 0.14.9 |
| implementation | io.circe | circe-parser_2.13 | 0.14.9 |
| implementation | org.typelevel | cats-effect_2.13 | 3.5.4 |
| implementation | org.tpolecat | doobie-core_2.13 | 1.0.0-RC5 |
| implementation | org.tpolecat | doobie-hikari_2.13 | 1.0.0-RC5 |
| implementation | org.tpolecat | doobie-postgres_2.13 | 1.0.0-RC5 |
| implementation | org.postgresql | postgresql | 42.7.3 |
| implementation | org.flywaydb | flyway-core | 10.17.0 |
| implementation | org.flywaydb | flyway-database-postgresql | 10.17.0 |
| implementation | com.github.pureconfig | pureconfig_2.13 | 0.17.7 |
| implementation | ch.qos.logback | logback-classic | 1.5.6 |
| implementation | org.typelevel | log4cats-slf4j_2.13 | 2.7.0 |
| testImplementation | org.scalatest | scalatest_2.13 | 3.2.19 |
| testImplementation | org.typelevel | cats-effect-testing-scalatest_2.13 | 1.5.0 |
| testImplementation | com.typesafe.akka | akka-http-testkit_2.13 | 10.6.3 |
| testImplementation | com.typesafe.akka | akka-stream-testkit_2.13 | 2.9.3 |
| testImplementation | com.typesafe.akka | akka-actor-testkit-typed_2.13 | 2.9.3 |
| testImplementation | org.testcontainers | testcontainers | 1.20.1 |
| testImplementation | org.testcontainers | postgresql | 1.20.1 |

Gradle plugins:
- `id 'scala'`
- `id 'application'` (main class: `com.habittracker.Main`)
- `id 'org.flywaydb.flyway' version '10.17.0'`

**New dependencies to confirm with the engineer before the Developer agent
installs them:**

- **Akka HTTP family** (`akka-http`, `akka-http-core`, `akka-stream`,
  `akka-actor-typed`, `akka-slf4j`, `akka-http-testkit`, `akka-stream-testkit`,
  `akka-actor-testkit-typed`) — replaces the previously listed http4s family
  per the engineer override.
- **`akka-http-circe`** (Heiko Seeberger) — the Circe bridge for Akka HTTP
  marshalling/unmarshalling. Chosen over spray-json because Circe is already
  the house JSON library and using spray-json would fork codec logic. See
  ADR-001 for the full rationale.
- Doobie, Flyway, PureConfig, Logback, log4cats, Testcontainers — unchanged,
  still standard choices for this stack.

Listing here so the engineer approves the footprint explicitly (per CLAUDE.md
rule "Do not install new dependencies without stating them explicitly").

**Licensing flag:** Akka 2.7+ and Akka HTTP 10.5+ are published under BSL 1.1.
Acceptable for this internal PoC per ADR-001; must be revisited before any
commercial use.

---

## Package structure under `backend/src/main/scala/`

```
com.habittracker
├── Main.scala                          # entry point — wires config, ActorSystem, IORuntime, DB, routes, Akka HTTP binding
├── config
│   └── AppConfig.scala                 # PureConfig case classes: server, db
├── domain
│   ├── Habit.scala                     # case class Habit
│   ├── Frequency.scala                 # sealed trait + Daily/Weekly/Custom
│   └── AppError.scala                  # sealed trait AppError + subtypes
├── http
│   ├── HabitRoutes.scala               # Akka HTTP Route for the 6 endpoints; bridges IO -> Future at each handler
│   ├── HabitCodecs.scala               # Circe encoders/decoders for Habit + DTOs
│   ├── JsonSupport.scala               # mixes in FailFastCirceSupport from akka-http-circe
│   ├── ErrorResponse.scala             # shared error envelope + Circe codec
│   ├── ErrorHandler.scala              # AppError -> Akka HTTP Route completion; Akka HTTP ExceptionHandler + RejectionHandler
│   └── dto
│       ├── CreateHabitRequest.scala    # incoming POST body
│       ├── UpdateHabitRequest.scala    # incoming PUT body
│       └── HabitResponse.scala         # outgoing habit JSON view
├── service
│   └── HabitService.scala              # orchestrates repo + validation; returns IO[Either[AppError, _]]
└── repository
    ├── HabitRepository.scala           # trait with IO-returning methods
    └── DoobieHabitRepository.scala     # Doobie implementation
```

### Test tree under `backend/src/test/scala/`
```
com.habittracker
├── http
│   ├── HabitRoutesSpec.scala           # uses Akka HTTP ScalatestRouteTest + a fake HabitService
│   └── HabitCodecsSpec.scala           # Circe round-trip tests
├── service
│   └── HabitServiceSpec.scala          # validation + orchestration tests
├── repository
│   └── DoobieHabitRepositorySpec.scala # hits Testcontainers Postgres
└── integration
    └── HabitApiIntegrationSpec.scala   # full stack: Akka HTTP bound to ephemeral port + Doobie + Testcontainers Postgres
```

---

## Effect-type boundary (from ADR-001)

Recorded here so the Developer agent applies it consistently:

- **Routes in `HabitRoutes`:** return `Route` / `Future[_]`. Never expose `IO`
  directly to the Akka HTTP runtime.
- **`HabitService` and below:** return `IO[Either[AppError, A]]` (or `IO[Unit]`,
  `IO[Option[A]]`, etc. as appropriate). Never return `Future`.
- **Bridge:** at each route handler, call `service.xxx(...).unsafeToFuture()`
  using the implicit `cats.effect.unsafe.IORuntime` that `Main` wires in. Then
  use Akka HTTP's `onComplete` / `onSuccess` directive to translate the
  `Either[AppError, A]` into an HTTP response.
- **No blocking.** `Await.result` is forbidden. All bridging must be async.

---

## Affected files

| File | Change type | Description |
|------|-------------|-------------|
| `backend/build.gradle` | Create | Scala plugin, Akka HTTP + akka-stream + akka-http-circe dependencies, Flyway plugin, application main class. |
| `backend/settings.gradle` | Create | rootProject.name = 'habit-tracker-backend'. |
| `backend/src/main/resources/application.conf` | Create | HOCON config consumed by PureConfig — server host/port, DB URL, DB user, DB password (env-var-backed). Also includes a minimal `akka { ... }` block (loglevel = INFO, default dispatcher). |
| `backend/src/main/resources/logback.xml` | Create | Minimal console logging config, routing Akka logs through SLF4J via `akka-slf4j`. |
| `backend/src/main/scala/com/habittracker/Main.scala` | Create | Entry point. Steps (in order): load `AppConfig` via PureConfig; create `ActorSystem[Nothing]` (typed); create `cats.effect.unsafe.IORuntime` (default global is fine); build `HikariTransactor[IO]` resource; run Flyway migrations; construct `HabitRepository`, `HabitService`, `HabitRoutes`; call `Http()(system).newServerAt(host, port).bind(routes.route)`; register a JVM shutdown hook that unbinds the server, terminates the `ActorSystem`, and shuts down the `IORuntime` / transactor resource cleanly. |
| `backend/src/main/scala/com/habittracker/config/AppConfig.scala` | Create | PureConfig case classes for server + DB. |
| `backend/src/main/scala/com/habittracker/domain/Habit.scala` | Create | `case class Habit(id: UUID, name: String, description: Option[String], frequency: Frequency, createdAt: Instant, updatedAt: Instant, deletedAt: Option[Instant])`. |
| `backend/src/main/scala/com/habittracker/domain/Frequency.scala` | Create | `sealed trait Frequency`; objects `Daily`, `Weekly`; `final case class Custom(value: String)`; `Frequency.parse(s: String): Either[String, Frequency]` (rejects blank, accepts "daily"/"weekly"; Custom is constructible from code but not from the API in this batch); `Frequency.asString(f: Frequency): String`. |
| `backend/src/main/scala/com/habittracker/domain/AppError.scala` | Create | `sealed trait AppError`; subtypes `ValidationError(message)`, `NotFound(message)`, `InvalidUuid(message)`. No `DatabaseError` — DB failures bubble up as IO errors and are mapped to 500 by the Akka HTTP `ExceptionHandler`. |
| `backend/src/main/scala/com/habittracker/http/ErrorResponse.scala` | Create | `final case class ErrorResponse(message: String)` + Circe codec. This is the shared shape for every 4xx/5xx body. |
| `backend/src/main/scala/com/habittracker/http/ErrorHandler.scala` | Create | `def toRoute(e: AppError): Route` that completes with the correct status + `ErrorResponse` body. Also defines an `ExceptionHandler` (mapping any unhandled `Throwable` to `500` + generic ErrorResponse) and a `RejectionHandler` (mapping malformed JSON, missing required fields, unsupported content type to `400` + ErrorResponse). Both handlers are installed at the top of `HabitRoutes.route`. |
| `backend/src/main/scala/com/habittracker/http/JsonSupport.scala` | Create | `trait JsonSupport extends de.heikoseeberger.akkahttpcirce.FailFastCirceSupport`. Mixed into `HabitRoutes` to provide `FromRequestUnmarshaller` / `ToResponseMarshaller` for Circe-encoded types. |
| `backend/src/main/scala/com/habittracker/http/HabitCodecs.scala` | Create | Circe `Encoder`/`Decoder` for `Frequency`, `HabitResponse`, `CreateHabitRequest`, `UpdateHabitRequest`, `ErrorResponse`. |
| `backend/src/main/scala/com/habittracker/http/dto/CreateHabitRequest.scala` | Create | `final case class CreateHabitRequest(name: String, description: Option[String], frequency: String)`. Validation rejects blank name and unknown frequency via `HabitService`. |
| `backend/src/main/scala/com/habittracker/http/dto/UpdateHabitRequest.scala` | Create | Identical shape to CreateHabitRequest; separate class to keep intent clear. |
| `backend/src/main/scala/com/habittracker/http/dto/HabitResponse.scala` | Create | `final case class HabitResponse(id: UUID, name: String, description: Option[String], frequency: String, createdAt: Instant, updatedAt: Instant)`. Note: `deletedAt` is NOT included — only active habits are ever surfaced, so it is always null and would be noise. |
| `backend/src/main/scala/com/habittracker/http/HabitRoutes.scala` | Create | `final class HabitRoutes(service: HabitService)(implicit runtime: IORuntime)` with a `route: Route` value covering the six endpoints. Uses the Akka HTTP `Route` DSL: `pathPrefix("habits") { ... }` with `pathEndOrSingleSlash` + `(post & entity(as[CreateHabitRequest]))` and `get { ... }`, plus `path(JavaUUID) { id => ... }` for `/habits/{id}` and `get`/`put`/`delete` under it. Each handler calls `service.xxx(...).unsafeToFuture()` and uses `onSuccess` to fold the `Either[AppError, A]` into a `complete(StatusCodes.X, payload)` or `ErrorHandler.toRoute(err)`. Registers the shared `ExceptionHandler` + `RejectionHandler` via `handleExceptions(...)` and `handleRejections(...)`. |
| `backend/src/main/scala/com/habittracker/service/HabitService.scala` | Create | `trait HabitService` + impl; validates DTOs, generates UUIDs and timestamps, calls repo, maps repo outcomes to `Either[AppError, Habit]`. Returns `IO[Either[AppError, A]]`. |
| `backend/src/main/scala/com/habittracker/repository/HabitRepository.scala` | Create | Trait with: `create(h: Habit): IO[Unit]`, `listActive(): IO[List[Habit]]`, `findActiveById(id: UUID): IO[Option[Habit]]`, `updateActive(id: UUID, name: String, description: Option[String], frequency: Frequency, updatedAt: Instant): IO[Option[Habit]]`, `softDelete(id: UUID, at: Instant): IO[Boolean]`. |
| `backend/src/main/scala/com/habittracker/repository/DoobieHabitRepository.scala` | Create | Doobie implementation. Uses `ConnectionIO` + `Transactor[IO]`. Each method does a single parametrised SQL. `updateActive` issues an `UPDATE ... WHERE id = ? AND deleted_at IS NULL RETURNING *` and returns `Option[Habit]` so the caller can distinguish 404 from success in one round-trip. `softDelete` returns `true` if and only if one row was updated. |
| `infra/db/migrations/V1__create_habits_table.sql` | Create | See "Database changes" section. |
| `.env.example` | Create | Documents `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `APP_PORT`, `APP_ENV` with placeholder values. |

---

## Database changes

**Migration file:** `infra/db/migrations/V1__create_habits_table.sql`

**Full SQL:**
```sql
-- V1__create_habits_table.sql
-- Creates the habits table for the Habit Tracker PoC.
-- See docs/adr/ADR-002-habit-schema.md for rationale.

CREATE TABLE habits (
    id           UUID         PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    frequency    VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    deleted_at   TIMESTAMPTZ
);

-- Partial index to keep active-habit lookups fast as soft-deleted rows accumulate.
CREATE INDEX idx_habits_active
    ON habits (id)
    WHERE deleted_at IS NULL;

-- A second partial index to accelerate GET /habits list queries ordered by creation.
CREATE INDEX idx_habits_active_created_at
    ON habits (created_at DESC)
    WHERE deleted_at IS NULL;
```

Flyway will read from `infra/db/migrations/` (configured in `build.gradle`
via `flyway { locations = ['filesystem:../infra/db/migrations'] }`).

No edits to the existing `infra/db/init/01_enable_vector.sql` file — that
remains the one-shot extension bootstrap. No future PBI should modify V1;
add a V2 instead.

---

## API contract

Base path: `/habits`. Content-Type for all bodies: `application/json; charset=utf-8`.
All timestamps are ISO-8601 UTC (e.g. `2026-04-16T10:30:00Z`).

### Shared error response shape
Used for every 4xx and 5xx response in this API:
```json
{ "message": "<human-readable description>" }
```
Defined by `com.habittracker.http.ErrorResponse`. No error codes, no stack
traces, no nested structures — PoC simplicity.

### 1. Create habit
- **Method:** `POST`
- **Path:** `/habits`
- **Request body:**
  ```json
  {
    "name": "Read 20 pages",
    "description": "Non-fiction preferred",
    "frequency": "daily"
  }
  ```
  - `name` (string, required, non-blank after trim)
  - `description` (string, optional; `null` and absent both allowed)
  - `frequency` (string, required; `"daily"` or `"weekly"` — any other value is 400)
- **Responses:**
  - `201 Created` — body: `HabitResponse`
    ```json
    {
      "id": "6f9619ff-8b86-d011-b42d-00c04fc964ff",
      "name": "Read 20 pages",
      "description": "Non-fiction preferred",
      "frequency": "daily",
      "createdAt": "2026-04-16T10:30:00Z",
      "updatedAt": "2026-04-16T10:30:00Z"
    }
    ```
  - `400 Bad Request` — ErrorResponse. Triggers: blank name, missing name,
    unknown frequency, malformed JSON (emitted by Akka HTTP
    `RejectionHandler`), wrong content type.

### 2. List habits
- **Method:** `GET`
- **Path:** `/habits`
- **Request body:** none
- **Responses:**
  - `200 OK` — bare JSON array (per engineer decision, no envelope):
    ```json
    [
      { "id": "...", "name": "...", "description": null, "frequency": "daily",
        "createdAt": "...", "updatedAt": "..." }
    ]
    ```
    Empty state is `[]`, not `404`. Ordering: `created_at DESC` (newest first).
    Only rows with `deleted_at IS NULL` are returned.

### 3. Get habit
- **Method:** `GET`
- **Path:** `/habits/{id}`
- **Path param:** `{id}` must be a valid UUID. In Akka HTTP this is enforced
  via the `JavaUUID` path matcher; a non-UUID segment triggers a rejection that
  the shared `RejectionHandler` maps to `400` with an ErrorResponse.
- **Responses:**
  - `200 OK` — body: `HabitResponse` (same shape as create).
  - `400 Bad Request` — ErrorResponse. Triggered when `{id}` is not a valid UUID.
  - `404 Not Found` — ErrorResponse. Triggered when no row matches, or the
    matching row has `deleted_at IS NOT NULL`.

### 4. Update habit
- **Method:** `PUT`
- **Path:** `/habits/{id}`
- **Request body:** same shape as Create (`name`, `description`, `frequency`).
  Full replacement semantics — all mutable fields must be present.
- **Responses:**
  - `200 OK` — body: `HabitResponse` with a refreshed `updatedAt` strictly
    later than the prior value. `createdAt` and `id` unchanged.
  - `400 Bad Request` — ErrorResponse. Triggered by invalid UUID, blank name,
    unknown frequency, malformed JSON.
  - `404 Not Found` — ErrorResponse. Triggered when no active row matches
    (row missing or soft-deleted).

### 5. Delete habit (soft)
- **Method:** `DELETE`
- **Path:** `/habits/{id}`
- **Request body:** none
- **Responses:**
  - `204 No Content` — no body.
  - `400 Bad Request` — ErrorResponse. Triggered when `{id}` is not a valid UUID.
  - `404 Not Found` — ErrorResponse. Triggered when no active row matches.
    Explicitly also returned for an already-deleted habit (engineer decision).

### Summary matrix

| # | Method | Path           | Success    | Validation error | Not found |
|---|--------|----------------|------------|------------------|-----------|
| 1 | POST   | /habits        | 201 + body | 400              | —         |
| 2 | GET    | /habits        | 200 + `[]` | —                | —         |
| 3 | GET    | /habits/{id}   | 200 + body | 400              | 404       |
| 4 | PUT    | /habits/{id}   | 200 + body | 400              | 404       |
| 5 | DELETE | /habits/{id}   | 204        | 400              | 404       |

---

## LLM integration
Not applicable for this PBI batch. These are pure CRUD endpoints. LLM-backed
endpoints (daily tip, pattern analysis, streak risk) land in later PBIs and
will be planned separately.

---

## Test plan

The Developer agent must produce the following tests. Integration tests use
Testcontainers to spin up PostgreSQL 17; the container is shared across the
test class via a `BeforeAndAfterAll` hook that runs Flyway migrations once.

Akka HTTP route tests use the official `akka-http-testkit` via
`ScalatestRouteTest`. Service tests remain `cats-effect` / `IO`-based via
`cats-effect-testing-scalatest`.

### Unit tests
- `HabitCodecsSpec`
  - Round-trip encode/decode for `HabitResponse`, `CreateHabitRequest`,
    `UpdateHabitRequest`.
  - `Frequency` encodes to `"daily"`/`"weekly"` and decodes back.
  - Decoding a `CreateHabitRequest` with a missing `name` fails.

- `HabitServiceSpec` (with an in-memory repo double)
  - Creating a habit with a blank name returns `ValidationError`.
  - Creating a habit with frequency `"monthly"` returns `ValidationError`.
  - Creating a habit with valid input returns a habit whose `id`, `createdAt`,
    `updatedAt` are set and `deletedAt` is None.
  - Updating a missing id returns `NotFound`.
  - Updating sets a strictly later `updatedAt` than the original `createdAt`
    (use an injected `Clock` or tick the fake clock between calls).

- `HabitRoutesSpec` (extends `ScalatestRouteTest` with a scripted fake service)
  - All six endpoints produce the status codes in the summary matrix above.
  - Uses `Post("/habits", requestJson) ~> routes.route ~> check { ... }`
    idiom from `akka-http-testkit`.
  - `GET /habits` with no data produces `200` and body `[]`.
  - `GET /habits/not-a-uuid` produces `400` with an ErrorResponse body
    (exercises the `JavaUUID` matcher + `RejectionHandler`).
  - Malformed JSON body on POST produces `400` with an ErrorResponse body
    (exercises the `RejectionHandler`).

### Repository tests (Testcontainers Postgres)
- `DoobieHabitRepositorySpec`
  - `create` + `findActiveById` round-trips every field, including a `null`
    description.
  - `listActive` returns only rows with `deleted_at IS NULL`, ordered by
    `created_at DESC`.
  - `updateActive` on a soft-deleted row returns `None` and does not touch
    the row.
  - `softDelete` sets `deleted_at` and returns `true`; a second call returns
    `false` without changing the row.
  - A raw SQL assertion confirms that after `softDelete`, the row is still
    physically present (PBI-006 acceptance criterion).

### Integration tests (full HTTP stack + Testcontainers)
- `HabitApiIntegrationSpec`
  - Starts a real Akka HTTP server via `Http().newServerAt("127.0.0.1", 0).bind(routes.route)`
    (port 0 = ephemeral) and tears it down in `afterAll` via
    `binding.unbind()` + `system.terminate()`.
  - Uses an Akka HTTP client (`Http().singleRequest(...)`) to exercise each
    endpoint end-to-end.
  - POST valid body -> 201; response has all expected fields populated.
  - POST missing name -> 400 with a `message` field.
  - POST with `frequency: "monthly"` -> 400.
  - GET /habits with two seeded active habits and one soft-deleted habit
    returns exactly the two active habits (PBI-003).
  - GET /habits/{id} for an existing active habit -> 200 with correct field
    values; for a random UUID -> 404; for a non-UUID path segment -> 400
    (PBI-004).
  - POST then PUT with new name -> 200; `updatedAt` strictly greater than
    the original; subsequent GET reflects the new name; PUT on a random UUID
    -> 404 (PBI-005).
  - POST then DELETE -> 204; subsequent GET /habits/{id} -> 404; direct SQL
    query confirms the row is still present with a non-null `deleted_at`;
    a second DELETE -> 404 (PBI-006).

### Acceptance coverage map
| PBI | Covered by |
|-----|------------|
| PBI-001 | Migration runs in Testcontainers; `Habit` and `Frequency` compile; all other specs as indirect evidence. |
| PBI-002 | HabitServiceSpec validation cases + HabitApiIntegrationSpec POST cases. |
| PBI-003 | HabitApiIntegrationSpec list case + DoobieHabitRepositorySpec `listActive`. |
| PBI-004 | HabitApiIntegrationSpec GET-by-id cases + HabitRoutesSpec 400 on non-UUID. |
| PBI-005 | HabitApiIntegrationSpec PUT cases + HabitServiceSpec update-missing case. |
| PBI-006 | HabitApiIntegrationSpec delete case including direct SQL assertion + DoobieHabitRepositorySpec `softDelete`. |

---

## Open questions

1. **Akka HTTP / Akka Streams version pinning.** Plan pins `akka-http` 10.6.3
   and `akka-stream` 2.9.3 as the paired versions. Confirm the engineer is
   happy with 10.6.x; alternative is staying on the 10.5.x line if there are
   concerns about 10.6 maturity. (Recommended: 10.6.3.)
2. **BSL licensing acknowledgement.** Akka 2.7+ / Akka HTTP 10.5+ are BSL 1.1.
   Confirm the engineer accepts this for the PoC; commercial promotion would
   require revisiting (sbt classic / Apache Pekko fork are the known escape
   hatches if licensing becomes a blocker later).
3. **`akka-http-circe` vs spray-json.** Plan chooses `akka-http-circe` for
   single-source-of-truth JSON codecs. Confirm — alternative is spray-json with
   a second set of codecs, which the architect does not recommend.
4. **Gradle Scala 2.13 toolchain.** Gradle's built-in Scala plugin supports
   Scala 2.13; confirm the engineer is happy to use the stock plugin rather
   than the community `gradle-scala-plugin`. (Recommended: stock plugin.)
5. **Flyway Gradle plugin vs Flyway-in-app.** The plan runs Flyway both from
   Gradle (`./gradlew flywayMigrate`, per CLAUDE.md) AND from the application
   on startup (so integration tests migrate automatically). Confirm this
   dual-mode is acceptable. (Recommended: yes, and both point at the same
   migrations folder.)
6. **Logging verbosity.** Default plan is INFO for `com.habittracker`, WARN
   for everything else; Akka internals at WARN. Confirm or override.
7. **Clock injection.** For deterministic `updated_at` tests the plan uses
   `cats.effect.kernel.Clock[IO]` passed into `HabitService`. Confirm this
   is acceptable (alternative: a simple `IO(Instant.now())` with
   best-effort monotonicity checks in tests).
8. **Content-type strictness.** Plan rejects requests without
   `Content-Type: application/json` with 400 (via Akka HTTP's default
   content-negotiation rejections). Confirm — the alternative is a lenient
   unmarshaller.
9. **Graceful shutdown timeout.** Akka HTTP's `binding.terminate(hardDeadline)`
   needs a deadline. Proposed: 10 seconds. Confirm.

---

## Summary of changes from the prior revision

The engineer overrode the framework choice from http4s to Akka HTTP. The
following were updated accordingly:

- **ADR-001** rewritten: title now records Akka HTTP as the chosen framework;
  http4s downgraded to a rejected alternative; the engineer-override rationale
  is documented; a new "Resolving the Future vs IO tension" section spells out
  the boundary-only bridging rule (`Future` at the HTTP layer, `IO` below it,
  `unsafeToFuture` / `IO.fromFuture` at the boundary, no `Await.result`); BSL
  licensing flagged as a trade-off.
- **Dependencies** — removed: `http4s-ember-server`, `http4s-ember-client`,
  `http4s-dsl`, `http4s-circe`. Added: `akka-http`, `akka-http-core`,
  `akka-stream`, `akka-actor-typed`, `akka-slf4j`, `akka-http-circe`
  (Seeberger), `akka-http-testkit`, `akka-stream-testkit`,
  `akka-actor-testkit-typed`. Circe, Doobie, Flyway, PureConfig, Logback,
  Testcontainers, ScalaTest unchanged. `akka-http-circe` chosen over
  spray-json because Circe is already the house JSON library — rationale
  recorded in ADR-001.
- **Package structure** — `HabitRoutes` now exposes an Akka HTTP `Route`
  instead of `HttpRoutes[IO]`; added `JsonSupport.scala` (mixes in
  `FailFastCirceSupport` from akka-http-circe); `ErrorHandler` gained an
  Akka HTTP `ExceptionHandler` and `RejectionHandler` in addition to the
  per-`AppError` mapping.
- **`Main.scala`** now wires an `ActorSystem[Nothing]` and an `IORuntime`
  side-by-side, binds via `Http().newServerAt(...).bind(...)`, and tears
  down both runtimes on shutdown.
- **New effect-type-boundary section** added to the plan body so the
  Developer agent applies the `Future` / `IO` rule consistently.
- **Test plan** — route tests now use `akka-http-testkit`'s
  `ScalatestRouteTest` (`~> routes.route ~> check { ... }`); integration
  tests bind a real Akka HTTP server on an ephemeral port and use
  `Http().singleRequest(...)` as the client.
- **Open questions** expanded with Akka HTTP version pinning, BSL licensing
  acknowledgement, `akka-http-circe` vs spray-json, and graceful shutdown
  timeout.

---

This technical plan is ready for your review. Please approve or request changes before I hand off to the Developer agent.
