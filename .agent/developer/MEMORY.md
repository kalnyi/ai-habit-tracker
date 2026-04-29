# Developer Agent Memory
# Habit Tracker · Agent Swarm
# Last updated: Phase 3 retrospective (2026-04-29)

---

## HabitContext Field Types (CRITICAL — phase briefs have errors)

HabitContext (com.habittracker.model.Analytics) as of Phase 3:
  userId:             Long
  streaks:            Map[UUID, Int]                  ← UUID, not Long
  completionByDay:    Map[String, Double]
  consistencyRanking: List[(String, Double)]
  timeOfDayPatterns:  Map[String, Double]              = Map.empty
  correlatedPairs:    List[(String, String, Double)]   = Nil
  momentumScores:     Map[UUID, Double]                = Map.empty  ← UUID, not Long
  retrievedTips:      List[String]                     = Nil

Phase 4 may add further fields — use Scala defaults for frozen-test compat (see below).

---

## HabitContext Defaults Pattern

When adding new fields to HabitContext, use Scala default values if a frozen test
constructs HabitContext with fewer arguments and cannot be modified (HARD LIMIT).
  newField: Map[UUID, Double] = Map.empty
  newField: List[String]      = Nil

The defaults are only for compile-time compat with frozen tests. Production code
(DefaultAnalyticsService.buildHabitContext) always supplies all fields by name.

---

## sttp Content-Type Rule (CRITICAL — learnt from Phase 3 bug)

In sttp v3, calling `.body(string)` adds `Content-Type: text/plain; charset=utf-8`
to the request. If you call `.header("content-type", "application/json")` BEFORE
`.body()`, the body setter overrides it (default replaceExisting=false).

**Always set Content-Type AFTER .body() with replaceExisting = true:**

```scala
basicRequest
  .post(uri"$url")
  .header("Authorization", s"Bearer $key")
  .body(bodyJson)
  .header("Content-Type", "application/json", replaceExisting = true)  // ← after body
  .response(asString)
```

Anthropic's API is lenient and accepts text/plain — so AnthropicClient works despite
having this bug. OpenAI (and most strict APIs) require application/json. AnthropicClient
is frozen so its bug is harmless; all NEW sttp callers must follow the correct pattern.

---

## Parallel Execution Pattern

Two patterns for concurrent IO:

1. parTupled — fixed set of independent IO calls with different return types:
   (io1, io2, io3, io4).parTupled
   Requires: import cats.syntax.parallel._

2. parTraverse — uniform collection of per-element IO calls:
   habits.parTraverse { h => ... }

Phase 3 uses parTupled in AnalyticsService (4 user-scoped aggregates) and
parTraverse for per-habit fan-outs (streak + momentum).
Phase 4 uses parTupled for parallel retrieval (tips + notes).

---

## Service Trait New-Method Pattern (CRITICAL)

When adding a new method to a service trait where a frozen test fake extends the trait:
  - Use a concrete default: `def newMethod(...): IO[...] = IO.raiseError(new NotImplementedError(...))`
  - Do NOT declare it abstract — this breaks frozen fakes (e.g. FakeHabitCompletionService)
  - The real DefaultXxxService overrides it

This pattern was established in Phase 3 for `HabitCompletionService.recordCompletionBatch`.

---

## Test Conventions

Use ScalaTest AnyWordSpec + @RunWith(classOf[JUnitRunner]).
Do NOT use munit-cats-effect or CatsEffectSuite — the codebase uses ScalaTest.

Testcontainers specs (require Docker):
  - Annotate with @Ignore (active, not commented out) above @RunWith
  - Add comment: // requires Docker - run manually
  - Use PostgreSQLContainer("postgres:17-alpine") for standard specs
  - Use DockerImageName.parse("pgvector/pgvector:pg17") for specs needing the vector extension
  - Run Liquibase via DirectoryResourceAccessor
  - Liquibase changelog path from backend/: Paths.get("../infra/db/changelog")

Specs requiring Docker AND live API key (e.g. SeedTipsIdempotencySpec):
  - @Ignore with comment: // requires Docker AND OPENAI_API_KEY - run manually

Pure unit tests (no IO, no Docker):
  - No @Ignore
  - Extend AnyWordSpec with Matchers
  - No IORuntime needed

---

## AnalyticsCodecs

com.habittracker.http.AnalyticsCodecs uses semiauto derivation:
  import io.circe.generic.semiauto._
  implicit val ... = deriveEncoder[...]
  implicit val ... = deriveDecoder[...]

UUID KeyEncoder and KeyDecoder are already defined in AnalyticsCodecs.
Do not redefine them. Add new response encoders/decoders to the same file.
Import AnalyticsCodecs._ in any route file handling HabitContext or related types.

---

## Build Commands

./gradlew compileScala — after creating each new file group
./gradlew test         — when all files compile; expect InsightPromptSpec to pass,
                         Testcontainers specs to be skipped (@Ignore)
grep -r "akka" src/    — run from backend/; 5 results in config files are expected,
                         no results in Scala source is the pass condition

---

## AppResources Wiring Pattern

Current route composition in AppResources.make (do not break this order):
  new DocsRoutes().routes <+>
  new InsightsRoutes(analyticsService).routes <+>
  new AnalysisRoutes(analyticsService).routes <+>
  new TipsRoutes(analyticsService, tipRepo).routes <+>
  new BatchCompletionRoutes(completionSvc).routes <+>
  new HabitRoutes(habitService).routes <+>
  new HabitCompletionRoutes(completionSvc).routes

Phase 4 adds NoteRoutes after BatchCompletionRoutes.
AnthropicClient.API_KEY_CHECK and EmbeddingClient.API_KEY_CHECK are already wired.

---

## Files That Must Not Be Modified in Phase 4

Per HARD LIMITS and established contract:
  backend/src/main/scala/com/habittracker/client/AnthropicClient.scala
  backend/src/main/scala/com/habittracker/client/EmbeddingClient.scala
  backend/src/main/scala/com/habittracker/prompt/InsightPrompt.scala
  backend/src/main/scala/com/habittracker/http/InsightsRoutes.scala
  backend/src/main/scala/com/habittracker/http/AnalysisRoutes.scala

TipsRoutes.scala IS modified in Phase 4 (parallel retrieval replaces sequential).
PromptBuilder.scala IS modified in Phase 4 (personalNotesSection + updated build).
Analytics.scala IS modified in Phase 4 (new case classes, updated TipsResponse).

All Phase 1, 2, and 3 test files are frozen EXCEPT TipsResponse-related tests which
must be updated for the Phase 4 TipsResponse field rename.

---

## Known Flaky Test

DoobieAnalyticsRepositorySpec — completionRateByDayOfWeek test:
  TODO: "test is incorrect when running on Wednesday. weekSpan is 3 weeks, not 2"
  Spec is @Ignore so this does not affect CI. Do not fix here — tracked as tech debt.

---

## Known Tech Debt (from Phase 2 and Phase 3 reviews)

1. HabitCompletionCodecsSpec — missing HabitCompletionResponse round-trip with completedAt populated
2. HabitCompletionCodecsSpec — missing BatchCompletionResponse / SkippedCompletion codec unit tests
3. SeedTipsIdempotencySpec — tests TipRepository directly, not SeedTips.run (add scope comment)
4. PromptBuilder streakSection and momentumSection render UUID instead of habit names
