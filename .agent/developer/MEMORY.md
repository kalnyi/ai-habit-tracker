# Developer Agent Memory
# Habit Tracker · Agent Swarm
# Last updated: Phase 2 retrospective (2026-04-29)

---

## HabitContext Field Types (CRITICAL — phase briefs have errors)

HabitContext (com.habittracker.model.Analytics) as of Phase 2:
  userId:             Long
  streaks:            Map[UUID, Int]                  ← UUID, not Long
  completionByDay:    Map[String, Double]
  consistencyRanking: List[(String, Double)]
  timeOfDayPatterns:  Map[String, Double]              = Map.empty
  correlatedPairs:    List[(String, String, Double)]   = Nil
  momentumScores:     Map[UUID, Double]                = Map.empty  ← UUID, not Long

Phase 3 adds: retrievedTips: List[String] = Nil

habitId is always UUID throughout the schema. Any brief showing Map[Long, Int] or
Map[Long, Double] for habitId keys is documentation drift — use UUID.

---

## HabitContext Defaults Pattern (CRITICAL — established Phase 2)

When adding new fields to HabitContext, use Scala default values if a frozen test
constructs HabitContext with fewer arguments and cannot be modified (HARD LIMIT).
  newField: Map[UUID, Double] = Map.empty
  newField: List[String]      = Nil

The defaults are only for compile-time compat with frozen tests. Production code
(DefaultAnalyticsService.buildHabitContext) always supplies all fields by name.
Apply this pattern for Phase 3's retrievedTips field.

---

## Parallel Execution Pattern (established Phase 2)

Two patterns for concurrent IO in AnalyticsService:

1. parTupled — fixed set of independent IO calls with different return types:
   (io1, io2, io3, io4).parTupled
   Requires: import cats.syntax.parallel._

2. parTraverse — uniform collection of per-element IO calls:
   habits.parTraverse { h =>
     (analyticsRepo.streakForHabit(h.id), analyticsRepo.momentumScore(userId, h.id))
       .parTupled.map { case (streak, momentum) => (h.id -> streak, h.id -> momentum) }
   }

Use parTupled for the user-scoped aggregates (fixed set), parTraverse for
per-habit fan-outs. Both are IO-safe and composable.

---

## Test Conventions

Use ScalaTest AnyWordSpec + @RunWith(classOf[JUnitRunner]).
Do NOT use munit-cats-effect or CatsEffectSuite — the codebase uses ScalaTest.

Testcontainers specs (require Docker):
  - Annotate with @Ignore (active, not commented out) above @RunWith
  - Add comment: // requires Docker - run manually
  - Use PostgreSQLContainer("postgres:17-alpine")
  - Run Liquibase via DirectoryResourceAccessor
  - Liquibase changelog path from backend/: Paths.get("../infra/db/changelog")

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

UUID KeyEncoder and KeyDecoder are already defined in AnalyticsCodecs for
Map[UUID, _] JSON keys. Do not redefine them in Phase 3.
Add new response encoders/decoders to AnalyticsCodecs (same file, not new file).
Import AnalyticsCodecs._ in any route file handling HabitContext or response types.

---

## Build Commands

./gradlew compileScala — after creating each new file group
./gradlew test         — when all files compile; expect InsightPromptSpec to pass,
                         DoobieAnalyticsRepositorySpec to be skipped (@Ignore)
grep -r "akka" src/    — run from backend/; 5 results in config files are expected,
                         no results in Scala source is the pass condition

---

## AppResources Wiring Pattern

Current route composition in AppResources.make (do not break this order):
  new DocsRoutes().routes <+>
  new InsightsRoutes(analyticsService).routes <+>
  new AnalysisRoutes(analyticsService).routes <+>
  new HabitRoutes(habitService).routes <+>
  new HabitCompletionRoutes(completionSvc).routes

Phase 3 adds TipsRoutes by appending after AnalysisRoutes.
AnthropicClient.API_KEY_CHECK force-init is already wired — do not add another.
Add OPENAI_API_KEY force-init separately for EmbeddingClient (Phase 3).

---

## Files That Must Not Be Modified in Phase 3

Per HARD LIMITS and established contract:
  backend/src/main/scala/com/habittracker/client/AnthropicClient.scala
  backend/src/main/scala/com/habittracker/prompt/InsightPrompt.scala
  backend/src/main/scala/com/habittracker/http/InsightsRoutes.scala
  backend/src/main/scala/com/habittracker/http/AnalysisRoutes.scala
  All existing Phase 1 and Phase 2 test files (InsightPromptSpec, PromptBuilderSpec,
  Phase 1+2 DoobieAnalyticsRepositorySpec methods)

PromptBuilder.scala IS modified in Phase 3 (adds retrievedContextSection and
updates build signature with default args) — this is explicitly allowed.

---

## Known Flaky Test

DoobieAnalyticsRepositorySpec — completionRateByDayOfWeek test:
  Line has a TODO: "test is incorrect when running on Wednesday. weekSpan is 3 weeks, not 2"
  Spec is @Ignore so this does not affect CI. When manually running on a Wednesday,
  the assertion Monday=1.0/Tuesday=0.5 may fail. Do not attempt to fix by changing
  the test date logic — this needs a separate fix tracked as tech debt.

---

## Known Tech Debt (from Phase 2 review)

1. HabitCompletionCodecsSpec — missing round-trip test with completedAt populated
   (Some(Instant)). All existing cases use completedAt = None. Not blocking.
2. DoobieAnalyticsRepositorySpec — timeOfDaySuccessPattern test has no evening/night
   seed data. AC-11 passes, coverage is thin. Not blocking.
3. PromptBuilder streakSection and momentumSection render habit IDs as raw UUIDs
   instead of habit names. Cosmetic for PoC. Not blocking.
