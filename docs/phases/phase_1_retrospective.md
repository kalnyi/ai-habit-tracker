# PHASE 1 RETROSPECTIVE
# Habit Tracker · Agent Swarm
# Generated: 2026-04-29

---

## SECTION 1: PATTERNS ESTABLISHED

Phase 2 must follow each of these patterns exactly.

---

PATTERN: Package rooting for new analytics/LLM source files
LOCATION: backend/src/main/scala/com/habittracker/model/Analytics.scala,
          backend/src/main/scala/com/habittracker/client/AnthropicClient.scala,
          backend/src/main/scala/com/habittracker/prompt/InsightPrompt.scala
RULE: Phase brief path references like `src/main/scala/model/Analytics.scala`
      map to the full path under `com.habittracker`:
        model/     → com.habittracker.model
        client/    → com.habittracker.client
        prompt/    → com.habittracker.prompt
      Phase 2 must place PromptBuilder.scala in
      `backend/src/main/scala/com/habittracker/prompt/PromptBuilder.scala`
      (package com.habittracker.prompt).

---

PATTERN: Route and service classes use IO directly (not F[_])
LOCATION: backend/src/main/scala/com/habittracker/http/InsightsRoutes.scala:19,
          backend/src/main/scala/com/habittracker/service/AnalyticsService.scala:9
RULE: All route files declare `HttpRoutes[IO]`. All service trait methods return
      `IO[_]`, not `F[_]: Async`. AnthropicClient.complete is polymorphic
      `F[_]: Async` but is called as `AnthropicClient.complete[IO](...)`.
      Phase 2 must not change this — do not introduce F[_] into route or service
      signatures.

---

PATTERN: AnalyticsRepository is the home for all analytical SQL queries
LOCATION: backend/src/main/scala/com/habittracker/repository/AnalyticsRepository.scala,
          backend/src/main/scala/com/habittracker/repository/DoobieAnalyticsRepository.scala
RULE: Phase 2's three new SQL queries (timeOfDaySuccessPattern,
      crossHabitCorrelation, momentumScore) go into AnalyticsRepository and
      DoobieAnalyticsRepository, not into HabitRepository. All method signatures
      use IO[_]. Trait methods take habitId as UUID, not Long.

---

PATTERN: AnthropicClient is a no-trait object, called directly at the route call site
LOCATION: backend/src/main/scala/com/habittracker/client/AnthropicClient.scala:10,
          backend/src/main/scala/com/habittracker/http/InsightsRoutes.scala:25
RULE: Phase 2 must call AnthropicClient.complete[IO] directly inside the
      /analysis route handler for-comprehension, passing
      PromptBuilder.HABIT_COACH_SYSTEM_PROMPT. No wrapping trait or abstract class.
      AnthropicClient.scala itself must not be modified.

---

PATTERN: InsightPrompt.build uses 4 named vals: header, streakSection, daySection, rankingSection
LOCATION: backend/src/main/scala/com/habittracker/prompt/InsightPrompt.scala:13-44
RULE: The brief specified 3 named section vals (streakSection, daySection,
      rankingSection). The implementation added a 4th val `header` and combines
      all 4 with List(header, streakSection, daySection, rankingSection).mkString("\n\n").
      Phase 2's PromptBuilder.build must follow the same named-val pattern;
      the InsightPrompt.scala file must remain unchanged.

---

PATTERN: Circe codecs use semiauto derivation (not auto), in a dedicated codecs file
LOCATION: backend/src/main/scala/com/habittracker/http/AnalyticsCodecs.scala
RULE: Phase 2 must add codecs for AnalysisResponse (and extended HabitContext) to
      AnalyticsCodecs.scala using deriveEncoder/deriveDecoder from
      io.circe.generic.semiauto. UUID KeyEncoder and KeyDecoder are already
      defined in AnalyticsCodecs — do not redefine them.

---

PATTERN: Testcontainers SQL tests use AnyWordSpec + @RunWith(JUnitRunner) + @Ignore
LOCATION: backend/src/test/scala/com/habittracker/repository/DoobieAnalyticsRepositorySpec.scala
RULE: Phase 2 may extend DoobieAnalyticsRepositorySpec or add a parallel spec.
      Any new Testcontainers spec must carry the @Ignore annotation (active, not
      commented out) and the @RunWith(classOf[JUnitRunner]) annotation.
      Pure unit tests (InsightPromptSpec pattern) use AnyWordSpec with no @Ignore.

---

PATTERN: API key startup check via AppResources
LOCATION: backend/src/main/scala/com/habittracker/AppResources.scala:31
RULE: `Resource.eval(IO(AnthropicClient.API_KEY_CHECK))` is already in the wiring
      graph. Phase 2 does not add another check — it already fires on startup
      because AnthropicClient is a Scala object (singleton).

---

PATTERN: ADR numbering and file location
LOCATION: docs/adr/ADR-008-phase1-pattern-detection.md
RULE: ADRs live under docs/adr/ (not docs/adrs/ as the index.md states). ADR
      numbering is sequential from all prior ADRs — Phase 1 used ADR-008.
      Phase 2 ADR must be written to docs/adr/ADR-009-phase2-habit-analysis.md.

---

## SECTION 2: TECHNICAL DEBT

---

DEBT: Leftover akka entries in resource files (not Scala source)
LOCATION: backend/src/main/resources/application.conf:17-20,
          backend/src/main/resources/logback.xml:13-14
RISK: `grep -r "akka" src/` returns 5 results from these config files, technically
      failing Done When items AC-14 and AC-16. These are configuration leftovers
      from the Akka HTTP → http4s migration (commit e558cd3). No Scala akka imports
      exist. If CI runs the literal grep check it will report failures. Phase 2
      must not add akka references but need not clean up these config entries
      (engineer decision required).

---

DEBT: completionRateByDayOfWeek test produces incorrect assertions when run on a Wednesday
LOCATION: backend/src/test/scala/com/habittracker/repository/DoobieAnalyticsRepositorySpec.scala:165
RISK: The test seeds completions relative to Monday. When today IS a Wednesday,
      Postgres DATE_TRUNC('week',...) includes the current week, making weekSpan
      3 instead of 2. Assertions for Monday=1.0 and Tuesday=0.5 fail. The spec
      carries @Ignore so it does not fail normal CI, but will produce a false
      negative on manual Docker runs on Wednesdays.

---

DEBT: No automated test for the /insights route handler
LOCATION: backend/src/main/scala/com/habittracker/http/InsightsRoutes.scala
RISK: The AnthropicClient.complete call is untestable without a live API key
      (per ADR-008 HARD LIMIT "no abstraction over the API call"). The route is
      verified manually only. Phase 2 inherits the same gap for /analysis.

---

DEBT: ANTHROPIC_API_KEY startup-failure path is not covered by an automated test
LOCATION: backend/src/main/scala/com/habittracker/client/AnthropicClient.scala:18-24
RISK: AC-7 is verified by manual run only (unset ANTHROPIC_API_KEY, run
      ./gradlew run, observe error). Scala object initialisation cannot be tested
      in the same JVM without a child-process harness. Phase 2 inherits this gap.

---

DEBT: DoobieHabitCompletionRepositorySpec runs in CI (pre-existing, not introduced by Phase 1)
LOCATION: backend/src/test/scala/com/habittracker/repository/DoobieHabitCompletionRepositorySpec.scala:23
RISK: The @Ignore annotation is commented out, so this spec runs without Docker
      and will fail in any CI without a Postgres container. Phase 2 must not
      worsen this by adding further commented-out @Ignore annotations.

---

## SECTION 3: PHASE 2 BRIEF AMENDMENTS

---

AMEND: STACK section — build tool
CHANGE: Phase 2 brief (docs/phases/phase_2_habit_analysis.md) lists `Build: sbt`
        under the STACK heading, identical to the Phase 1 brief error.
        ADR-008 section 7 and PBI-013 Technical Note 1 both document that this
        project uses Gradle. The phase_2_habit_analysis.md STACK section still
        says sbt and the Done When checklist says `sbt test`.
ORIGINAL: "- Build:       sbt" and "- [ ] sbt test passes in full with zero failures"
CORRECTED: "- Build:       Gradle (./gradlew)" and "- [ ] ./gradlew test passes in full with zero failures"

---

AMEND: HabitContext.streaks field type in Phase 2 Scope section
CHANGE: Phase 2 brief defines HabitContext with `streaks: Map[Long, Int]`.
        Phase 1 implemented `streaks: Map[UUID, Int]` (confirmed in
        Analytics.scala:7 and PLAN-013). habitId is UUID throughout the schema
        (habits.id is UUID per ADR-002 and PBI-012 AC-8).
ORIGINAL: "streaks:            Map[Long, Int],"
CORRECTED: "streaks:            Map[UUID, Int],"

---

AMEND: HabitContext.momentumScores field type in Phase 2 Scope section
CHANGE: Phase 2 brief defines `momentumScores: Map[Long, Double]`.
        habitId is UUID, not Long (same root cause as streaks amendment above).
ORIGINAL: "momentumScores:     Map[Long, Double]          // habitId -> momentum (-1.0 to +1.0)"
CORRECTED: "momentumScores:     Map[UUID, Double]          // habitId -> momentum (-1.0 to +1.0)"

---

AMEND: momentumScore SQL query signature in Phase 2 Scope section
CHANGE: Phase 2 brief declares `momentumScore(userId: Long, habitId: Long): F[Double]`.
        habitId must be UUID to match HabitRepository.listActive return type and
        AnalyticsRepository.streakForHabit(habitId: UUID) convention.
ORIGINAL: "**momentumScore(userId: Long, habitId: Long): F[Double]**"
CORRECTED: "**momentumScore(userId: Long, habitId: UUID): F[Double]**"

---

AMEND: ADR reference path in Phase 2 execution context and agent instructions
CHANGE: Phase 2 brief instructs agents to read
        `docs/adrs/ADR-001-phase1-pattern-detection.md`.
        The actual path is `docs/adr/ADR-008-phase1-pattern-detection.md`.
        Directory is `docs/adr/` not `docs/adrs/`. File is ADR-008, not ADR-001.
ORIGINAL: "Read docs/phases/phase_1_pbi.md and docs/adrs/ADR-001-phase1-pattern-detection.md"
CORRECTED: "Read docs/phases/phase_1_pbi.md and docs/adr/ADR-008-phase1-pattern-detection.md"

---

AMEND: AGENT INSTRUCTIONS — Architect section, ADR output path
CHANGE: Phase 2 brief says Architect writes to
        `docs/adrs/ADR-002-phase2-habit-analysis.md`.
        Correct directory is `docs/adr/` and correct sequential number is 009.
ORIGINAL: "Write output to: docs/adrs/ADR-002-phase2-habit-analysis.md"
CORRECTED: "Write output to: docs/adr/ADR-009-phase2-habit-analysis.md"

---

## SECTION 4: MEMORY UPDATES REQUIRED

---

FILE: .agent/architect/MEMORY.md
SECTION: File Layout
UPDATE:
  All new source files live under com.habittracker.*:
  - LLM composition types  → com.habittracker.model  (e.g. HabitContext, InsightResponse)
  - External API clients   → com.habittracker.client (e.g. AnthropicClient)
  - Prompt construction    → com.habittracker.prompt (e.g. InsightPrompt, PromptBuilder)
  - HTTP route classes     → com.habittracker.http   (e.g. InsightsRoutes)
  - Analytical SQL traits  → com.habittracker.repository (AnalyticsRepository)
  Phase brief path references like src/main/scala/model/ map to
  backend/src/main/scala/com/habittracker/model/. Never create top-level packages
  outside com.habittracker.

---

FILE: .agent/architect/MEMORY.md
SECTION: ADR location and numbering
UPDATE:
  ADRs live at docs/adr/ (NOT docs/adrs/ — index.md is wrong on this).
  Prior ADR numbers: 001-007 for pre-phase work, 008 for Phase 1.
  Phase 2 ADR must be docs/adr/ADR-009-phase2-habit-analysis.md.

---

FILE: .agent/architect/MEMORY.md
SECTION: AnthropicClient constraints
UPDATE:
  AnthropicClient (com.habittracker.client.AnthropicClient) is a no-trait,
  no-constructor Scala object. Never propose a trait or abstract class around it.
  ANTHROPIC_API_KEY is read at object-init; startup failure is forced via
  Resource.eval(IO(AnthropicClient.API_KEY_CHECK)) in AppResources.make.
  This object must not be modified in Phase 2 or beyond.

---

FILE: .agent/architect/MEMORY.md
SECTION: Build tool
UPDATE:
  Build tool is Gradle (./gradlew test, ./gradlew compileScala).
  Phase briefs say "sbt" in their STACK and Done When sections — this is
  documentation drift. All agent instructions and plans must use ./gradlew.

---

FILE: .agent/developer/MEMORY.md
SECTION: HabitContext field types
UPDATE:
  HabitContext (com.habittracker.model.Analytics) Phase 1 fields:
    userId:             Long
    streaks:            Map[UUID, Int]       ← UUID, not Long
    completionByDay:    Map[String, Double]
    consistencyRanking: List[(String, Double)]
  Phase 2 adds:
    timeOfDayPatterns:  Map[String, Double]
    correlatedPairs:    List[(String, String, Double)]
    momentumScores:     Map[UUID, Double]    ← UUID, not Long
  Any phase brief showing Map[Long, Int] or Map[Long, Double] for habitId keys
  is incorrect — habitId is always UUID throughout the codebase.

---

FILE: .agent/developer/MEMORY.md
SECTION: Test conventions
UPDATE:
  All tests use ScalaTest AnyWordSpec + @RunWith(classOf[JUnitRunner]).
  Do NOT use munit-cats-effect or CatsEffectSuite despite phase brief STACK
  references — the codebase uses ScalaTest throughout.
  Testcontainers specs MUST carry active @Ignore (not commented-out) and the
  comment "// requires Docker - run manually" above it.
  Pure unit tests (no IO, no Docker) do NOT carry @Ignore.
  Liquibase migrations path for Testcontainers specs:
    Paths.get("../infra/db/changelog").toAbsolutePath.normalize
    (one level up from backend/, not two — verify against existing specs).

---

FILE: .agent/developer/MEMORY.md
SECTION: AnalyticsCodecs
UPDATE:
  com.habittracker.http.AnalyticsCodecs uses semiauto derivation (deriveEncoder,
  deriveDecoder from io.circe.generic.semiauto). UUID KeyEncoder and KeyDecoder
  are defined there for Map[UUID,_] JSON serialisation. When Phase 2 adds
  AnalysisResponse, add its encoder/decoder to AnalyticsCodecs (do not create
  a separate file). Import AnalyticsCodecs._ in any route that handles
  HabitContext or AnalysisResponse.

---

FILE: .agent/reviewer/MEMORY.md
SECTION: Known non-blocking gaps accepted by ADR-008
UPDATE:
  These gaps are accepted trade-offs documented in ADR-008, not defects:
  1. No unit test for InsightsRoutes (or AnalysisRoutes in Phase 2) —
     AnthropicClient.complete is not stubbable (HARD LIMIT).
  2. ANTHROPIC_API_KEY startup failure verified manually only.
  3. grep -r "akka" src/ returns 5 results from application.conf and logback.xml
     (config leftovers from pre-Phase-1 http4s migration). No Scala akka imports
     exist. Report as known debt, not as a blocking failure.

---

FILE: .agent/reviewer/MEMORY.md
SECTION: Done When checklist — build tool correction
UPDATE:
  Phase briefs reference "sbt test" in Done When checklists.
  Actual command is ./gradlew test. Reviewer must run ./gradlew test, not sbt test,
  and report results against that command.

---

After producing this report, apply all MEMORY updates listed above.
Confirm each update below.

---

## SECTION 5: PHASE READINESS

READY — Phase 2 can start with the following caveats:

All core Done When items pass:
- GET /users/{userId}/habits/insights returns HTTP 200: PASS (implementation complete)
- Response deserialises to InsightResponse with analytics and narrative: PASS
- analytics contains streaks (Map[UUID,Int]), completionByDay (Map[String,Double] all 7 days),
  consistencyRanking (List[(String,Double)] sorted desc): PASS
- narrative is non-empty string produced by Anthropic API: PASS
- HabitContext and InsightResponse exist in model/Analytics.scala only: PASS
- AnthropicClient is a direct sttp request object (no wrapping trait): PASS
- ANTHROPIC_API_KEY read from environment, startup failure on missing key: PASS
- InsightPrompt.SYSTEM_PROMPT is a named constant: PASS
- InsightPrompt.build uses named vals (header, streakSection, daySection, rankingSection): PASS
- InsightPrompt.build is a pure function (def build(ctx: HabitContext): String): PASS
- All 4 InsightPrompt unit tests pass (InsightPromptSpec): PASS
- All 4 SQL query tests exist in DoobieAnalyticsRepositorySpec: PASS (require Docker to run)
- No Future return types in route/service/repository: PASS
- No akka Scala imports in src/: PASS (akka references exist only in config files,
  not in Scala source)

Non-blocking items for engineer awareness before Phase 2 starts:
1. Apply the 5 amendments in Section 3 to docs/phases/phase_2_habit_analysis.md
   before the BA agent produces the Phase 2 PBI.
2. Clean up akka entries in application.conf and logback.xml if strict grep
   compliance is required (engineer decision).
3. completionRateByDayOfWeek test may fail on Wednesdays (spec is @Ignore — no
   CI impact, but flag for manual test days).
