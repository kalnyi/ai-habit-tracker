# PBI-013: Phase 1 — Pattern Detection and LLM Narrative Endpoint

## User story

As the habit tracker application, I want a single endpoint that runs analytical
SQL queries over a user's habit and completion data, assembles the results into a
typed context object, and sends that context to the Anthropic API to produce a
human-readable narrative, so that the system has an end-to-end LLM interaction
pattern that subsequent phases can build on.

---

## Acceptance criteria

- [ ] **AC-1 — GET /users/{userId}/habits/insights returns HTTP 200.**
  A GET request to `/users/{userId}/habits/insights` with a valid `userId` (Long)
  returns HTTP 200. The response body is valid JSON.

- [ ] **AC-2 — Response body deserialises to InsightResponse.**
  The JSON response deserialises without error to an `InsightResponse` case class
  with two fields: `analytics` (type `HabitContext`) and `narrative` (type `String`).

- [ ] **AC-3 — analytics contains all three required fields.**
  The `analytics` field in the response contains:
  - `streaks`: a map from habit UUID to current consecutive-day streak (Map[UUID, Int])
  - `completionByDay`: a map from day name to completion rate (Map[String, Double]),
    with all 7 day names present (Monday–Sunday), even where the rate is 0.0
  - `consistencyRanking`: a list of (habitName, score) tuples sorted descending by score

- [ ] **AC-4 — narrative is a non-empty string.**
  The `narrative` field in the response is a non-empty string produced by a call to
  the Anthropic API (`claude-sonnet-4-20250514` model). It is not a placeholder or
  hardcoded value.

- [ ] **AC-5 — HabitContext and InsightResponse defined in model/Analytics.scala.**
  The file `src/main/scala/model/Analytics.scala` exists and contains exactly these
  two case classes:
  ```scala
  case class HabitContext(
    userId:             Long,
    streaks:            Map[UUID, Int],
    completionByDay:    Map[String, Double],
    consistencyRanking: List[(String, Double)]
  )

  case class InsightResponse(
    analytics: HabitContext,
    narrative: String
  )
  ```
  No other file defines these types.

- [ ] **AC-6 — AnthropicClient contains a direct sttp request with no wrapping trait or class.**
  `src/main/scala/client/AnthropicClient.scala` exists as an `object`. The sttp HTTP
  request to `https://api.anthropic.com/v1/messages` is constructed and executed at the
  call site inside `AnthropicClient.complete`. There is no wrapping trait, abstract class,
  or interface around the client. The model constant is `claude-sonnet-4-20250514`.

- [ ] **AC-7 — ANTHROPIC_API_KEY is read from environment, not hardcoded.**
  `AnthropicClient` reads the API key exclusively from the `ANTHROPIC_API_KEY`
  environment variable. The key does not appear as a literal string anywhere in
  source code. If `ANTHROPIC_API_KEY` is absent at startup, the application raises
  a clear, descriptive error and does not start.

- [ ] **AC-8 — InsightPrompt.SYSTEM_PROMPT is a named constant.**
  `src/main/scala/prompt/InsightPrompt.scala` exists and declares `SYSTEM_PROMPT`
  as a named `val` (or `final val`) directly on the `InsightPrompt` object. It is
  not inlined at the call site in the route handler.

- [ ] **AC-9 — InsightPrompt.build uses named vals for each section.**
  The `InsightPrompt.build(ctx: HabitContext): String` method constructs the user
  message by assigning each logical section to a named `val` (e.g. `streakSection`,
  `daySection`, `rankingSection`) before combining them. Anonymous string literals
  are not passed directly to the final concatenation step.

- [ ] **AC-10 — InsightPrompt.build is a pure function.**
  `InsightPrompt.build` has no `F[_]` type parameter, no `IO`, and no side effects.
  Its signature is `def build(ctx: HabitContext): String`.

- [ ] **AC-11 — All 4 SQL query unit tests pass.**
  The following four test cases exist and pass with `./gradlew test`:
  - `streakForHabit`: seed 3 consecutive days, assert streak = 3
  - `streakForHabit`: seed with a gap on the day before today, assert streak = 0
  - `completionRateByDayOfWeek`: seed known completions, assert expected rates
  - `habitConsistencyRanking`: seed 2 habits with different completion rates,
    assert descending order

- [ ] **AC-12 — All 4 InsightPrompt unit tests pass.**
  The following four pure unit tests exist and pass with `./gradlew test`:
  - `build(ctx)` output contains the userId value from ctx
  - `build(ctx)` output contains at least one habit name from ctx
  - `build(ctx)` output contains at least one day name from ctx
  - `build(ctx)` produces non-empty strings for all 3 named section vals

- [ ] **AC-13 — ./gradlew test passes in full with zero failures.**
  Running `./gradlew test` from the project root produces zero test failures and
  zero compilation errors. All pre-existing tests continue to pass unchanged.

- [ ] **AC-14 — No akka imports anywhere in src/.**
  Running `grep -r "akka" src/` from the backend directory returns no results.
  The migration from Akka HTTP to http4s (completed before this phase) is
  confirmed clean.

- [ ] **AC-15 — No Future return types in any route handler or service method.**
  Every route handler and every service method uses `F[_]` (or `IO`) as its
  effect type. No method signature in `routes/`, `service/`, or `repository/`
  packages returns `scala.concurrent.Future`.

- [ ] **AC-16 — grep -r "akka" src/ returns no results.**
  This is a separate, independently-run verification of AC-14. The Reviewer agent
  must run this command and report its output verbatim.

---

## Out of scope

- **Embeddings, pgvector, or RAG.** This phase uses no vector store. The narrative
  is produced from a single structured prompt — no retrieval step.
- **Frontend changes.** No frontend work in this PBI.
- **Streaming responses.** The Anthropic API is called in standard request/response
  mode only.
- **LangChain4j or any LLM framework.** The HTTP call to Anthropic is made directly
  via sttp.
- **Changes to existing CRUD endpoints or their tests.** Habit and completion CRUD
  endpoints are not modified by this PBI.
- **Phase 2 analytics endpoint** (`GET /users/{userId}/habits/analysis`). That is out
  of scope here; Phase 1 adds only the insights endpoint.
- **Authentication or user creation.** The `userId` in the path is trusted
  unconditionally. Only the seeded default user (id=1) is required.
- **Error handling beyond startup failure.** A missing or invalid Anthropic API
  response returns an appropriate HTTP 5xx; detailed error classification is deferred.

---

## Technical notes for the Architect

### 1. Build tool correction
The phase brief references `sbt test`. This project uses **Gradle**, not sbt. All
test and compile commands must use `./gradlew test` and `./gradlew compileScala`.
The Architect's ADR should document this correction for agent traceability.

### 2. habitId type
`habits.id` is `UUID` (confirmed by PBI-012 AC-8 and engineer decision 2026-04-22).
`HabitContext.streaks` is `Map[UUID, Int]`. All SQL queries involving `habitId`
use UUID. `userId` is `Long` (from PBI-012).

### 3. SQL query placement
The three new analytical queries (`streakForHabit`, `completionRateByDayOfWeek`,
`habitConsistencyRanking`) may be added to the existing `HabitRepository` or placed
in a new `AnalyticsRepository`. The Architect must decide and document the choice in
the ADR. Either approach is acceptable; consistency with the existing repository
pattern is preferred.

### 4. AnthropicClient wiring
`AnthropicClient` is a Scala `object` with a single method `complete[F[_]: Async]`.
It requires no trait, no DI framework, and no abstract class. The Architect must
decide where `ANTHROPIC_API_KEY` is read (at object initialisation vs. at call time)
and how a missing key surfaces as an application startup error. This decision requires
an ADR entry.

### 5. ADR required
This PBI introduces architectural decisions that require an ADR before the Developer
agent proceeds:
- File structure for new source files (`model/Analytics.scala`,
  `client/AnthropicClient.scala`, `prompt/InsightPrompt.scala`)
- How `AnthropicClient` is wired into the route without a wrapping trait
- How the insights endpoint is registered in `AppResources`
- SQL query placement (HabitRepository vs. AnalyticsRepository)
- Test approach for SQL queries (Testcontainers with real Postgres vs. H2)
- ANTHROPIC_API_KEY read strategy and startup failure mechanism

### 6. LLM use case mapping
This PBI implements the **pattern analysis** LLM use case from CLAUDE.md:
> "weekly summary of habit completion patterns, correlations between habits,
> and risk flags."
The system prompt in `InsightPrompt.SYSTEM_PROMPT` defines the analysis scope.

### 7. Stack versions (read-only)
http4s 0.23.27, Cats Effect 3, Doobie, Circe (`io.circe.generic.auto._`),
sttp with cats-effect backend, munit-cats-effect / CatsEffectSuite.
No new external dependencies are needed — sttp is already on the classpath.

### 8. Phase 2 contracts (do not modify)
`model/Analytics.scala`, `client/AnthropicClient.scala`, and
`prompt/InsightPrompt.scala` are carry-over contracts for Phase 2.
Phase 2 adds fields to `HabitContext` and adds `PromptBuilder.scala` following
the same named-val convention. Phase 1 files must not be designed in a way that
makes these extensions difficult.

---

## Layer
Layer 2 (habit tracker app)

## Estimated complexity
L
