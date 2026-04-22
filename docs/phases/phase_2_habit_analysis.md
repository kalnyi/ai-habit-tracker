# PHASE 2 — HABIT ANALYSIS
# Habit Tracker · Agent Swarm Brief

## EXECUTION CONTEXT
This file is the single source of truth for Phase 2.
Agents must read this file before taking any action.
All agents in this phase receive the HARD LIMITS section as a system prompt constraint.
Do not start Phase 2 until docs/phases/phase_1_review.md confirms all Done When items pass.
Read docs/phases/phase_1_pbi.md and docs/adrs/ADR-001-phase1-pattern-detection.md
before starting — Phase 2 builds on Phase 1 patterns exactly.

## STACK (read-only reference, do not change)
- HTTP:        http4s + Ember server
- Effects:     Cats Effect 3 — all async is F[_]: Async or IO, no Future
- Database:    Doobie + Postgres (Docker Compose)
- JSON:        Circe via http4s-circe, io.circe.generic.auto._
- HTTP client: sttp with cats-effect backend
- Testing:     munit-cats-effect, CatsEffectSuite
- Build:       sbt
- LLM model:   claude-sonnet-4-20250514

---

## INHERITED FROM PHASE 1 (do not modify these)

```
src/main/scala/model/Analytics.scala     — HabitContext, InsightResponse
src/main/scala/client/AnthropicClient.scala — direct sttp Anthropic call
src/main/scala/prompt/InsightPrompt.scala   — SYSTEM_PROMPT, build(ctx)
GET /users/{userId}/habits/insights               — unchanged, tests must still pass
```

Any change to the above files is a HARD LIMIT violation.

---

## GOAL

Deepen the analysis layer built in Phase 1. Add three new SQL queries covering
time-of-day patterns, cross-habit correlations, and momentum trends. Introduce
a PromptBuilder with named section methods that assembles a structured,
multi-section prompt. Engineers learn that prompt construction is testable
business logic and that system prompt design shapes LLM output quality.

---

## DELIVERABLE

```
GET /users/{userId}/habits/analysis
→ 200 OK
→ body: AnalysisResponse
```

```scala
case class AnalysisResponse(
  analytics: HabitContext,   // extended HabitContext from Phase 1
  narrative: String
)
```

---

## SCOPE

### 1. Extend HabitContext
Edit src/main/scala/model/Analytics.scala.
Add three new fields. Do not remove or rename existing fields.

```scala
case class HabitContext(
  // Phase 1 fields — unchanged
  userId:             Long,
  streaks:            Map[Long, Int],
  completionByDay:    Map[String, Double],
  consistencyRanking: List[(String, Double)],

  // Phase 2 additions
  timeOfDayPatterns:  Map[String, Double],       // bucket -> completion rate
  correlatedPairs:    List[(String, String, Double)], // (habitA, habitB, coOccurrenceRate)
  momentumScores:     Map[Long, Double]          // habitId -> momentum (-1.0 to +1.0)
)
```

Time-of-day buckets must use exactly these keys:
"morning" (06:00–11:59), "afternoon" (12:00–17:59),
"evening" (18:00–21:59), "night" (22:00–05:59)

Momentum score definition:
  completionRate(last30Days) - completionRate(prior30Days)
  Range: -1.0 (fully declined) to +1.0 (fully improved)
  If fewer than 7 completions in either window, return 0.0 (insufficient data)

### 2. New SQL queries
Add to AnalyticsRepository (or HabitRepository if Phase 1 used that):

**timeOfDaySuccessPattern(userId: Long): F[Map[String, Double]]**
For each time bucket, rate of days-with-completion across all user habits.
Derive bucket from the hour of the completion timestamp.
Return all 4 buckets even if rate is 0.0.

**crossHabitCorrelation(userId: Long): F[List[(String, String, Double)]]**
For each pair of habits owned by the user, compute the rate of days on which
both habits were completed (co-occurrence rate).
Return the top 3 pairs sorted by co-occurrence rate descending.
If the user has fewer than 2 habits, return empty list.

**momentumScore(userId: Long, habitId: Long): F[Double]**
Compute as defined above.
Called once per habit in buildHabitContext — use parSequence or parTraverse
to run all momentum queries in parallel.

### 3. Extend buildHabitContext
Update the service method from Phase 1 to run all 6 queries and populate
the extended HabitContext. The 3 new queries run alongside the 3 Phase 1
queries. Use parTupled or parTraverse where queries are independent.

### 4. New file: src/main/scala/prompt/PromptBuilder.scala

Structured prompt construction with named section methods.
Every method must be pure (no F[_], no IO).
Every method must be independently unit-testable.

```scala
object PromptBuilder {

  val HABIT_COACH_SYSTEM_PROMPT: String =
    """You are a habit coach. You receive structured data about a user's
      |habit patterns and provide specific, actionable behavioural insights.
      |Always reference specific habit names and data points.
      |Format: 4-6 sentences grouped into observations and one recommendation."""
      .stripMargin

  def streakSection(ctx: HabitContext): String          = ...
  def dayPatternSection(ctx: HabitContext): String       = ...
  def rankingSection(ctx: HabitContext): String          = ...
  def timeOfDaySection(ctx: HabitContext): String        = ...
  def correlationSection(ctx: HabitContext): String      = ...
  def momentumSection(ctx: HabitContext): String         = ...

  def build(ctx: HabitContext): String =
    List(
      streakSection(ctx),
      dayPatternSection(ctx),
      rankingSection(ctx),
      timeOfDaySection(ctx),
      correlationSection(ctx),
      momentumSection(ctx)
    ).filter(_.nonEmpty).mkString("\n\n")
}
```

InsightPrompt.scala from Phase 1 is NOT replaced. PromptBuilder is a new,
separate object. Phase 1 endpoint continues using InsightPrompt.

### 5. Add AnalysisResponse case class
Add to src/main/scala/model/Analytics.scala alongside existing classes:

```scala
case class AnalysisResponse(
  analytics: HabitContext,
  narrative: String
)
```

### 6. New endpoint
Add to AnalyticsRoutes (or create it) and register in AppResources:

```scala
case GET -> Root / "users" / LongVar(userId) / "habits" / "analysis" =>
  for {
    ctx       <- service.buildHabitContext(userId)
    prompt    =  PromptBuilder.build(ctx)
    narrative <- AnthropicClient.complete(PromptBuilder.HABIT_COACH_SYSTEM_PROMPT, prompt)
    response  =  AnalysisResponse(analytics = ctx, narrative = narrative)
    result    <- Ok(response)
  } yield result
```

### 7. Tests required

**SQL query tests** (TestContainers or H2):
- timeOfDaySuccessPattern: seed completions at known hours → assert bucket rates
- crossHabitCorrelation: seed 3 habits with known co-occurrence → assert top pair
- crossHabitCorrelation: user with 1 habit → assert empty list returned
- momentumScore: seed improving habit → assert positive score
- momentumScore: fewer than 7 completions in window → assert 0.0 returned

**PromptBuilder unit tests** (pure, no IO):
- each section method: given non-empty HabitContext, returns non-empty string
- build: output contains all 6 section contents when ctx is fully populated
- build: sections with no relevant data return empty string and are filtered out
- HABIT_COACH_SYSTEM_PROMPT: is a non-empty named constant

**Integration test**:
- Seed test user with habits and completions
- GET /users/{userId}/habits/analysis returns 200
- Response analytics contains all 6 HabitContext fields
- Response narrative is non-empty string

---

## HARD LIMITS
```
NO embeddings, vector operations, or pgvector
NO RAG retrieval of any kind — all context comes from SQL queries only
NO changes to Phase 1 GET /users/{userId}/habits/insights endpoint
NO changes to InsightPrompt.scala
NO changes to AnthropicClient.scala
NO removal or renaming of existing HabitContext fields
NO frontend changes
NO streaming responses
PromptBuilder.build must be a pure function — no F[_], no IO
HABIT_COACH_SYSTEM_PROMPT must be a named constant in PromptBuilder
Each section method must be independently unit-testable
Phase 1 tests must still pass in full — do not modify Phase 1 test files
```

---

## DONE WHEN (Reviewer checklist)

Run each item as an independent verification. Report pass/fail per item.
Do not mark phase complete until all items pass.

- [ ] GET /users/{userId}/habits/analysis returns HTTP 200
- [ ] Response body deserialises to AnalysisResponse
- [ ] analytics contains all 6 HabitContext fields (3 Phase 1 + 3 Phase 2)
- [ ] HabitContext has no removed or renamed fields vs Phase 1
- [ ] narrative is a non-empty string
- [ ] PromptBuilder.scala exists with 6 named section methods
- [ ] HABIT_COACH_SYSTEM_PROMPT is a named constant in PromptBuilder
- [ ] PromptBuilder.build is a pure function (no F[_], no IO)
- [ ] InsightPrompt.scala is unmodified from Phase 1
- [ ] AnthropicClient.scala is unmodified from Phase 1
- [ ] All 5 SQL query unit tests pass
- [ ] All PromptBuilder unit tests pass (section methods + build + constant)
- [ ] Integration test passes with seeded data
- [ ] GET /users/{userId}/habits/insights still returns HTTP 200 (Phase 1 regression)
- [ ] Phase 1 test files pass without modification
- [ ] sbt test passes in full with zero failures

---

## AGENT INSTRUCTIONS BY ROLE

### BA agent
Read Goal, Deliverable, and Scope sections.
Also read docs/phases/phase_1_pbi.md to understand what Phase 1 established.
Produce a PBI with acceptance criteria mapped 1:1 to the Done When checklist.
Write output to: docs/phases/phase_2_pbi.md

### Architect agent
Read the PBI, this full file, and ADR-001 from Phase 1.
The HARD LIMITS block is part of your constraints.
Produce an ADR covering: PromptBuilder design decisions, how the 6 new queries
are run (parallel vs sequential), how AnalysisResponse is structured.
Write output to: docs/adrs/ADR-002-phase2-habit-analysis.md

### Developer agent
Read the PBI, the ADR, the Scope section of this file, and the Phase 1 ADR.
The HARD LIMITS block is part of your constraints.
Implement all items in Scope.
Run sbt compile after each new file. Run sbt test when all files are complete.
Verify Phase 1 endpoint still works before reporting done.
Fix all failures before reporting done.

### Reviewer agent
Read the Done When checklist, the Phase 2 ADR, and the Phase 1 ADR.
Verify each checklist item independently.
Explicitly verify Phase 1 regression items.
Run: sbt test and report pass/fail counts.
Write review output to: docs/phases/phase_2_review.md
Report blocking issues separately from minor observations.

---

## CARRY-OVER TO PHASE 3

Phase 3 reads this section to understand what it inherits. Do not modify these
contracts during Phase 2 — if something was built differently, update the Phase 3
file before starting Phase 3.

```
FILE: src/main/scala/model/Analytics.scala
  HabitContext — Phase 3 ADDS one optional field:
    retrievedTips: List[String] = Nil
  AnalysisResponse — unchanged in Phase 3

FILE: src/main/scala/prompt/PromptBuilder.scala
  Phase 3 ADDS a new section method: retrievedContextSection(tips: List[String])
  Phase 3 ADDS tips parameter to build: build(ctx, tips: List[String] = Nil)
  Existing section methods unchanged

FILE: src/main/scala/client/AnthropicClient.scala
  Phase 3 reuses without modification

ENDPOINT: GET /users/{userId}/habits/analysis
  Unchanged in Phase 3

ENDPOINT: GET /users/{userId}/habits/insights
  Unchanged in Phase 3

PATTERN: PromptBuilder section methods are pure, named, independently testable
  Phase 3 retrievedContextSection must follow same pattern
```
