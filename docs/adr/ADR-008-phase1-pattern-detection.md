# ADR-008: Phase 1 pattern detection — file layout, AnthropicClient shape, analytics SQL placement, startup key validation

## Status
Accepted

## Context

PBI-013 (Phase 1 — Pattern Detection and LLM Narrative Endpoint) introduces
the first end-to-end LLM interaction in this codebase: three analytical SQL
queries over a user's habits and completions, a pure prompt builder, a direct
sttp call to Anthropic, and a single new endpoint
`GET /users/{userId}/habits/insights` that returns both the analytics and the
LLM narrative.

The Phase 1 brief (`docs/phases/phase_1_pattern_detection.md`) and PBI-013
Technical Notes both call out six architectural questions that need an
explicit resolution before the Developer agent starts:

1. **File structure for new sources.** Three carry-over files must exist at
   specific paths (`model/Analytics.scala`, `client/AnthropicClient.scala`,
   `prompt/InsightPrompt.scala`). The question is which Scala package each one
   lives in, how it relates to the existing `com.habittracker.*` layout, and
   whether the phase-brief paths are used verbatim or rooted under the project
   package.

2. **AnthropicClient wiring without a wrapping trait.** The HARD LIMITS block
   forbids an abstraction over the HTTP call ("HTTP call must be visible at
   the call site"). The existing service/route layers lean on a trait-per-
   collaborator pattern (`HabitService`, `HabitRepository`), so a consistent
   wiring story is needed.

3. **Insights endpoint registration in `AppResources`.** The app's single DI
   surface is the `for`-comprehension in `AppResources.make`. The new route
   graph has to compose with the existing three (`DocsRoutes`,
   `HabitRoutes`, `HabitCompletionRoutes`) without disturbing them or their
   tests.

4. **SQL query placement: `HabitRepository` vs new `AnalyticsRepository`.**
   The three new queries (`streakForHabit`, `completionRateByDayOfWeek`,
   `habitConsistencyRanking`) are read-only, cross-cutting, and only consumed
   by the new analytics code path — they do not share call sites with the
   CRUD methods on `HabitRepository`.

5. **Test approach for the SQL queries.** The project has two established
   patterns: Testcontainers + real Postgres (used by
   `DoobieHabitRepositorySpec`, `DoobieHabitCompletionRepositorySpec`, and
   the two integration specs) and pure unit tests for services using an
   `InMemoryHabitRepository`. Analytics queries contain SQL-dialect-specific
   constructs (`DATE - INTERVAL`, `EXTRACT(DOW ...)`, window functions), so
   H2 is not a viable substitute.

6. **`ANTHROPIC_API_KEY` read strategy and startup failure.** The key must
   come from the environment and never appear as a literal in source.
   AC-7 requires the application to refuse to start with a descriptive error
   when the key is missing. The options are: fail at `AnthropicClient`
   object-init time, fail at the first call site, or fail at
   `AppResources.make` while constructing the program.

---

## Decision

### 1. File layout — new sources live under `com.habittracker.{model,client,prompt}`

The Phase 1 brief's paths (`src/main/scala/model/Analytics.scala`, etc.) are
interpreted as paths *relative to the existing package root*. All three new
files are placed under `com.habittracker`:

| Phase brief path                              | Actual file path                                                            | Package                    |
|-----------------------------------------------|-----------------------------------------------------------------------------|----------------------------|
| `src/main/scala/model/Analytics.scala`        | `backend/src/main/scala/com/habittracker/model/Analytics.scala`             | `com.habittracker.model`   |
| `src/main/scala/client/AnthropicClient.scala` | `backend/src/main/scala/com/habittracker/client/AnthropicClient.scala`      | `com.habittracker.client`  |
| `src/main/scala/prompt/InsightPrompt.scala`   | `backend/src/main/scala/com/habittracker/prompt/InsightPrompt.scala`        | `com.habittracker.prompt`  |

Rationale:
- Every existing main-source file lives under `com.habittracker.*`
  (`domain`, `repository`, `service`, `http`, `config`). Rooting the new
  files directly under `src/main/scala/model/...` would create top-level
  packages (`model`, `client`, `prompt`) that sit beside `com.habittracker`,
  fragment the package hierarchy, and break ScalaDoc and IDE navigation.
- Three new packages are introduced deliberately. `domain` is reserved for
  canonical, CRUD-side case classes (`Habit`, `User`, `HabitCompletion`,
  `Frequency`, `AppError`). `model` holds LLM-facing composition types
  (`HabitContext`, `InsightResponse`) that are not domain entities but
  assembly records. Keeping them separate prevents drift between "thing the
  DB stores" and "thing the LLM receives".
- Phase 2 extends `HabitContext` by adding fields (per the carry-over block
  at the end of the phase brief). A dedicated `com.habittracker.model`
  package leaves room for additional analytics/LLM composition types
  (`PromptBuilder.scala` goes into `com.habittracker.prompt` alongside
  `InsightPrompt`) without crowding `domain`.

The two case classes in `Analytics.scala` are **the only** definitions in
that file (AC-5 "no other file defines these types"). `HabitContext` fields
are in the exact order the PBI prescribes.

### 2. `AnthropicClient` is a package-private `object` called directly from the route

`AnthropicClient` is a Scala `object` with a single method, consumed
**directly from the route handler** for the insights endpoint. There is no
trait, no `final class`, no interface, no DI binding, and no constructor
parameter list.

```scala
package com.habittracker.client

import cats.effect.Async
import cats.syntax.all._
import sttp.client3._
import sttp.client3.circe._
import io.circe.generic.auto._
import io.circe.Json

object AnthropicClient {

  val MODEL:      String = "claude-sonnet-4-20250514"
  val API_URL:    String = "https://api.anthropic.com/v1/messages"
  val API_VERSION: String = "2023-06-01"
  val MAX_TOKENS: Int    = 1024

  def complete[F[_]: Async](
      systemPrompt: String,
      userMessage:  String
  ): F[String] = {
    // constructs sttp request with headers, body, parses response,
    // returns content(0).text — implementation in the PLAN
  }
}
```

The route handler invokes `AnthropicClient.complete[IO](sys, user)`
inline inside the `for`-comprehension. The route file's `import
com.habittracker.client.AnthropicClient` line is the only mention of
the client anywhere in the route / service layer. The HTTP call is thus
visible at the call site as required by the HARD LIMITS.

Consequence for testability: the insights route handler is not
independently unit-testable without a live HTTP fixture. That is an
accepted cost of the "no abstraction over the API call" constraint, and
it matches the same trade-off accepted for the three other kinds of raw
SQL in the codebase — we test the route end-to-end via the server-level
integration spec (when engineer runs Docker manually, same as every
other integration spec) and we test the logic that surrounds the call
(`InsightPrompt`, the SQL queries, the context assembly) as pure or
Testcontainers units.

The key insight: Phase 2 will add a second LLM use-case (`/analysis`)
that reuses `AnthropicClient.complete` unmodified, passing a different
`SYSTEM_PROMPT`. An abstraction layer would hide this reuse; the direct
call makes it obvious.

### 3. `ANTHROPIC_API_KEY` is read at `AnthropicClient` object init with a startup failure

The key is read **once** at `AnthropicClient` object initialisation time
via `sys.env.get("ANTHROPIC_API_KEY")`:

- If the variable is missing or blank, object initialisation raises
  `sys.error("ANTHROPIC_API_KEY environment variable is not set. The
  habit tracker app cannot start without it.")`. The `scala.sys.error`
  throws `sys.SystemError`; this is caught by the JVM, propagates out
  of `Main.run`, and the app exits with a non-zero status.
- If the variable is present, the value is stored in a
  `private val API_KEY: String` inside the object.
- `AnthropicClient.complete` reads `API_KEY` and threads it into the
  sttp request's `x-api-key` header.

The startup failure is triggered **at the moment the first reference to
`AnthropicClient` is resolved**. The simplest way to guarantee that is
"at `AppResources.make`", which constructs a lightweight side-effect to
force object initialisation:

```scala
_ <- Resource.eval(IO(AnthropicClient.API_KEY_CHECK)) // forces init, raises if missing
```

Where `API_KEY_CHECK: Unit` is an explicit named forcing-handle on the
object. (The alternative — letting the first HTTP request blow up at
call time — violates AC-7 because the app *would* start.)

Rationale:
- Reading once at object init matches Scala's standard pattern for
  environment-backed configuration constants.
- Forcing the object from `AppResources.make` ties "app starts" to "key
  is present". The startup sequence is: transactor → `API_KEY_CHECK` →
  route construction. If the key is missing, Cats Effect IOApp prints
  the exception and exits before the server binds to a port.
- No PureConfig entry is needed — the key is not a HOCON setting, it is
  a secret. `AppConfig` continues to handle non-secret settings only
  (DB, port).

### 4. Insights endpoint lives in a new `InsightsRoutes`, wired in `AppResources`

A new route class `com.habittracker.http.InsightsRoutes` owns the single
new endpoint. It takes two constructor parameters:

```scala
final class InsightsRoutes(
    habitRepo:      HabitRepository,
    analyticsRepo:  AnalyticsRepository
) {
  val routes: HttpRoutes[IO] = ... // see plan for route body
}
```

Two reasons to keep it separate from `HabitRoutes`:
- `HabitRoutes` is CRUD-only. Phase 1 and Phase 2 add analytics/LLM
  endpoints (`/insights` now, `/analysis` next). Clustering them in an
  `InsightsRoutes` / analytics-namespaced file keeps the CRUD file small
  and prevents the CRUD tests from dragging in LLM-related imports.
- The insights route has two collaborators (habit repo for name lookup,
  analytics repo for the three SQL queries) plus a direct
  `AnthropicClient` reference. That is a different dependency shape from
  `HabitRoutes`, which only depends on `HabitService`.

A thin `AnalyticsService` is introduced with the single method the PBI
requires:

```scala
trait AnalyticsService {
  def buildHabitContext(userId: Long): IO[HabitContext]
}
```

This service method runs the three SQL queries (via `AnalyticsRepository`
plus one call to `HabitRepository.listActive` for names) and returns the
assembled `HabitContext`. The insights route then invokes
`AnthropicClient.complete` directly — keeping the HTTP call at the route
call site per HARD LIMIT.

Wiring in `AppResources.make` (preserving ADR-007's `UserRepository`
exposure):

```scala
for {
  xa              <- DatabaseConfig.transactor
  _               <- Resource.eval(IO(AnthropicClient.API_KEY_CHECK))
  userRepo         = new DoobieUserRepository(xa)
  habitRepo        = new DoobieHabitRepository(xa)
  completionRepo   = new DoobieHabitCompletionRepository(xa)
  analyticsRepo    = new DoobieAnalyticsRepository(xa)
  habitService     = new DefaultHabitService(habitRepo, Clock[IO])
  completionSvc    = new DefaultHabitCompletionService(habitRepo, completionRepo, Clock[IO])
  analyticsService = new DefaultAnalyticsService(habitRepo, analyticsRepo)
  allRoutes        = new DocsRoutes().routes <+>
                     new HabitRoutes(habitService).routes <+>
                     new HabitCompletionRoutes(completionSvc).routes <+>
                     new InsightsRoutes(analyticsService).routes
} yield AppResources(allRoutes, userRepo)
```

The existing three route classes are unchanged (HARD LIMIT: "NO changes
to existing CRUD endpoints or their tests"). The `InsightsRoutes`
constructor takes `AnalyticsService` (not the repos directly) — the
route stays thin; the repo composition happens in the service.

### 5. SQL queries live in a new `AnalyticsRepository`, not in `HabitRepository`

A new `AnalyticsRepository` trait and `DoobieAnalyticsRepository`
implementation hold the three SQL queries:

```scala
trait AnalyticsRepository {
  def streakForHabit(habitId: UUID): IO[Int]
  def completionRateByDayOfWeek(userId: Long): IO[Map[String, Double]]
  def habitConsistencyRanking(userId: Long): IO[List[(String, Double)]]
}
```

Rationale:
- **Different data shape.** `HabitRepository` returns `Habit` and
  `Option[Habit]`. The analytics queries return primitives (`Int`,
  `Map`, tuples). Co-locating them would require a separate implicit
  `Read` block per query inside the existing file and dilute its
  single-responsibility.
- **Different user-scoping pattern.** `HabitRepository` consistently
  enforces `AND user_id = $userId` on every SELECT (per ADR-007).
  `streakForHabit(habitId: UUID)` is scoped by `habit_id`, not
  `user_id` — it trusts that the caller (the analytics service) has
  already verified ownership via `habitRepo.listActive(userId)`.
  Mixing both scoping models in one repo blurs the rule reviewers rely
  on ("every HabitRepository query carries `AND user_id = ?`").
- **Matches Phase 2 growth.** Phase 2 adds more analytical queries.
  A dedicated repository grows naturally; extending
  `HabitRepository` keeps inflating one file with methods that share no
  call sites with the CRUD methods.
- **Testability.** `AnalyticsRepository` can have its own Testcontainers
  spec with its own fixture builder, independent of the existing
  `DoobieHabitRepositorySpec`. The two specs share the Postgres
  container startup cost but not the test logic.

`DoobieAnalyticsRepository` uses the existing pattern: `sql"..."
.query[T]` with `doobie.postgres.implicits._` for `LocalDate` and
`Instant`. No new Doobie configuration is needed.

### 6. SQL query tests use Testcontainers + real Postgres, not H2

The three analytical queries use PostgreSQL-specific constructs that
H2 does not implement faithfully:
- `completionRateByDayOfWeek` uses `EXTRACT(DOW FROM completed_on)` and
  `DATE_TRUNC('week', completed_on)`. H2 supports `EXTRACT` with a
  different day-of-week numbering (1-7 Sunday-first vs Postgres 0-6
  Sunday-first) and has no compatible `DATE_TRUNC('week', ...)`.
- `streakForHabit` uses `CURRENT_DATE - n * INTERVAL '1 day'` and a
  recursive or `generate_series`-based streak counter.
- `habitConsistencyRanking` divides completion count by
  `GREATEST(1, EXTRACT(EPOCH FROM (NOW() - created_at)) / 86400)`,
  which is Postgres-idiomatic.

The project already has Testcontainers + Postgres 17 set up
(`DoobieHabitRepositorySpec`, `DoobieHabitCompletionRepositorySpec`,
and both integration specs). `DoobieAnalyticsRepositorySpec` follows
the same template verbatim: `PostgreSQLContainer` start in `beforeAll`,
Liquibase `update("")` against the changelog at
`Paths.get("../../infra/db/changelog").toAbsolutePath.normalize`,
`HikariTransactor` in `runtime.compute`, and `@Ignore` so CI without
Docker skips the spec (matching the team convention).

No new Liquibase changeset is required for PBI-013 — the analytical
queries read existing `habits` and `habit_completions` columns.

### 7. Build tool is Gradle, not sbt (correction from phase brief)

The phase brief's STACK section lists `Build: sbt`, and its
Done-When checklist references `sbt test`. The repository uses
`./gradlew`. All commands and CI invocations are `./gradlew test` and
`./gradlew compileScala`. This ADR records the correction; no schema
or code change follows from it.

---

## Consequences

**Easier:**
- CRUD repository stays single-purpose; analytics queries evolve
  independently in `AnalyticsRepository` without touching
  `HabitRepository` signatures or tests.
- The Anthropic API key presence is checked exactly once, at startup,
  producing a loud failure with a clear operator-facing message instead
  of a mysterious 500 on the first insights request.
- `AnthropicClient.complete` reads as a self-contained 20-line method
  any reviewer can audit end-to-end — request construction, headers,
  body, response decoding, error propagation all in one place.
- Phase 2 adds `PromptBuilder.scala` into `com.habittracker.prompt`
  and a new analytics method on `AnalyticsRepository` without touching
  Phase 1 files.
- `InsightsRoutes` is its own test surface. Adding Phase 2's
  `/analysis` endpoint is a local edit to that class or a parallel
  `AnalysisRoutes`.

**Harder / trade-offs:**
- The insights route's direct `AnthropicClient.complete` call means
  there is **no unit test** that exercises the route handler with a
  stubbed LLM. The tests cover (a) `InsightPrompt.build` as a pure
  function, (b) the three SQL queries with Testcontainers, and (c)
  `AnalyticsService.buildHabitContext` composition — the LLM call
  itself is only exercised by the manual live-API run or a future
  integration spec that records/replays HTTP traffic. This is an
  explicit consequence of the HARD LIMIT "no abstraction over the API
  call".
- `AnthropicClient` is an `object` (singleton). Testing the
  startup-failure path requires either JVM-level environment
  manipulation or a dedicated sub-process test, neither of which is
  cheap. AC-7 is therefore verified by manual engineer action
  (unset the var, run `./gradlew run`, observe error), not by
  automated test. The Reviewer agent will note this.
- `AnalyticsRepository` and `HabitRepository` diverge slightly in
  user-scoping style: analytics trusts the caller has already scoped
  ownership. This is called out explicitly in scaladoc on
  `AnalyticsRepository` to keep future maintainers honest.
- The `API_KEY_CHECK` handle on `AnthropicClient` is a small pattern
  wart (a named `Unit` val used only for its side-effect). It is the
  simplest way to make "object init happens at startup, not at first
  request" observable, given the object has no public constructor to
  hook into.

**Locked in:**
- Three new packages (`com.habittracker.model`,
  `com.habittracker.client`, `com.habittracker.prompt`) are
  established. New LLM or analytics code goes into those. Analytics
  DTOs do not leak into `com.habittracker.domain`.
- `AnthropicClient` is a no-trait, no-constructor `object`. Phase 2
  reuses it unmodified with a different `SYSTEM_PROMPT` constant
  passed from `InsightPrompt` or `PromptBuilder`. Any future need to
  swap Anthropic for another provider requires a new ADR and a new
  file — not a trait extraction here.
- `ANTHROPIC_API_KEY` is read from the environment at object-init
  time. Config-file backing (HOCON, dotenv loading in Scala) is out
  of scope. Gradle's `dotEnv` file parser covers the developer-laptop
  case for runtime startup — no change to the dev workflow.
- `AnalyticsRepository` is the home for read-only analytical queries
  across both Phase 1 and Phase 2. Writes continue to live in the
  existing CRUD repositories.
- Gradle (`./gradlew test`) is the build tool. The phase brief's sbt
  references are documentation drift and are not authoritative.
