# PHASE 1 — PATTERN DETECTION
# Habit Tracker · Agent Swarm Brief

## EXECUTION CONTEXT
This file is the single source of truth for Phase 1.
Agents must read this file before taking any action.
All agents in this phase receive the HARD LIMITS section as a system prompt constraint.
Do not start this phase until the stack migration (migration_guide.md) is complete and verified.

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

## GOAL

Extend the existing Scala CRUD with three SQL analytical queries over habit and
completion data. Assemble query results into a typed context object. Send that
context to the Anthropic API via a direct sttp HTTP call. Return both the raw
analytics and the LLM-generated narrative from a single endpoint.

This phase establishes the foundational LLM interaction pattern for all
subsequent phases. Every step must be explicit and readable — no abstractions
over the API call, no frameworks, no magic.

---

## DELIVERABLE

```
GET /users/{userId}/habits/insights
→ 200 OK
→ body: InsightResponse
```

```scala
case class InsightResponse(
  analytics: HabitContext,
  narrative: String
)
```

---

## SCOPE

### 1. New SQL queries
Add to HabitRepository or a new AnalyticsRepository if separation is cleaner.

**streakForHabit(habitId: UUID): F[Int]**
Count consecutive days with a completion record up to and including today.
If today has no completion, streak is 0.

**completionRateByDayOfWeek(userId: Long): F[Map[String, Double]]**
For each day name (Monday–Sunday), the percentage of weeks that day had
at least one completion across any habit owned by the user.
Return all 7 days even if rate is 0.0.

**habitConsistencyRanking(userId: Long): F[List[(String, Double)]]**
All habits for the user sorted descending by consistency score.
Consistency score = total completions / days since habit was created (minimum 1).
Return list of (habitName, score) tuples.

### 2. New case classes
Create in a dedicated file: src/main/scala/model/Analytics.scala

```scala
case class HabitContext(
  userId:             Long,
  streaks:            Map[UUID, Int],           // habitId -> current streak
  completionByDay:    Map[String, Double],       // dayName -> completion rate
  consistencyRanking: List[(String, Double)]     // habitName -> score, sorted desc
)

case class InsightResponse(
  analytics: HabitContext,
  narrative: String
)
```

### 3. New service method
Add to HabitService or create AnalyticsService if preferred:

```scala
def buildHabitContext(userId: Long): F[HabitContext]
```

Runs all 3 queries and assembles HabitContext. No LLM call in this method.
Pure data assembly only.

### 4. New file: src/main/scala/client/AnthropicClient.scala

Direct sttp call to Anthropic API. The HTTP request must be visible at the
call site — do not hide it behind a trait or abstract class.

```scala
import cats.effect.Async
import sttp.client3._
import sttp.client3.circe._

object AnthropicClient {

  val MODEL:      String = "claude-sonnet-4-20250514"
  val API_URL:    String = "https://api.anthropic.com/v1/messages"
  val MAX_TOKENS: Int    = 1024

  def complete[F[_]: Async](
    systemPrompt: String,
    userMessage:  String
  ): F[String] = {
    // construct sttp request here — visible, not delegated
    // parse response and return content[0].text
    // handle non-200 responses by raising an error in F
  }
}
```

The Anthropic API key must be read from environment variable ANTHROPIC_API_KEY.
Do not hardcode it. Raise a clear error at startup if the variable is missing.

### 5. New file: src/main/scala/prompt/InsightPrompt.scala

Prompt construction logic. Must be a pure object with no IO or F[_].

```scala
object InsightPrompt {

  val SYSTEM_PROMPT: String =
    """You are a habit analysis assistant. You receive structured data about
      |a user's habit completion patterns and provide specific, data-grounded
      |observations. Be concise: 3-5 sentences. Reference specific habit names
      |and numbers from the data provided.""".stripMargin

  def build(ctx: HabitContext): String = {
    // assembles the user message from HabitContext fields
    // each section must be a named val within this method, not inlined
    // example structure:
    //   val streakSection    = ...
    //   val daySection       = ...
    //   val rankingSection   = ...
    //   List(streakSection, daySection, rankingSection).mkString("\n\n")
  }
}
```

### 6. New endpoint
Add to HabitRoutes or a new AnalyticsRoutes and register in AppResources:

```scala
case GET -> Root / "users" / LongVar(userId) / "habits" / "insights" =>
  for {
    ctx      <- service.buildHabitContext(userId)
    prompt   =  InsightPrompt.build(ctx)
    narrative <- AnthropicClient.complete(InsightPrompt.SYSTEM_PROMPT, prompt)
    response =  InsightResponse(analytics = ctx, narrative = narrative)
    result   <- Ok(response)
  } yield result
```

### 7. Tests required

**SQL query tests** (use TestContainers with real Postgres or H2):
- streakForHabit: seed 3 consecutive days → assert streak = 3
- streakForHabit: seed with a gap yesterday → assert streak = 0
- completionRateByDayOfWeek: seed known completions → assert expected rates
- habitConsistencyRanking: seed 2 habits with different rates → assert order

**InsightPrompt tests** (pure unit tests, no IO):
- build(ctx): assert output contains userId
- build(ctx): assert output contains at least one habit name from ctx
- build(ctx): assert output contains at least one day name from ctx
- build(ctx): assert all 3 named section vals appear as non-empty strings

---

## HARD LIMITS
```
NO LangChain4j or any LLM framework of any kind
NO embeddings, vector operations, or pgvector
NO frontend changes
NO streaming responses — standard request/response only
NO abstraction layer wrapping AnthropicClient — HTTP call must be at call site
NO changes to existing CRUD endpoints or their existing tests
InsightPrompt.build must be a pure function — no F[_], no IO, no side effects
SYSTEM_PROMPT must be a named constant in InsightPrompt — not inlined at call site
Each section in InsightPrompt.build must be a named val — not anonymous strings
Model must be claude-sonnet-4-20250514 — not opus, not haiku
ANTHROPIC_API_KEY must come from environment — never hardcoded
```

---

## DONE WHEN (Reviewer checklist)

Run each item as an independent verification. Report pass/fail per item.
Do not mark phase complete until all items pass.

- [ ] GET /users/{userId}/habits/insights returns HTTP 200
- [ ] Response body deserialises to InsightResponse with analytics and narrative
- [ ] analytics contains streaks, completionByDay, and consistencyRanking fields
- [ ] narrative is a non-empty string
- [ ] HabitContext and InsightResponse exist in model/Analytics.scala
- [ ] AnthropicClient contains a direct sttp request — no wrapping trait or class
- [ ] ANTHROPIC_API_KEY is read from environment, not hardcoded
- [ ] InsightPrompt.SYSTEM_PROMPT is a named constant
- [ ] InsightPrompt.build uses named vals for each section
- [ ] InsightPrompt.build is a pure function (no F[_], no IO)
- [ ] All 4 SQL query unit tests pass
- [ ] All 4 InsightPrompt unit tests pass
- [ ] sbt test passes in full with zero failures
- [ ] No akka imports anywhere in src/
- [ ] No Future return types in any route handler or service method
- [ ] grep -r "akka" src/ returns no results

---

## AGENT INSTRUCTIONS BY ROLE

### BA agent
Read Goal, Deliverable, and Scope sections.
Produce a PBI with acceptance criteria mapped 1:1 to the Done When checklist.
Write output to: docs/phases/phase_1_pbi.md

### Architect agent
Read the PBI and this full file.
The HARD LIMITS block is part of your constraints — do not propose anything that
violates them.
Produce an ADR covering: file structure decisions, how AnthropicClient is wired,
how the endpoint is registered, test approach.
Write output to: docs/adrs/ADR-001-phase1-pattern-detection.md

### Developer agent
Read the PBI, the ADR, and the Scope section of this file.
The HARD LIMITS block is part of your constraints.
Implement all items in Scope.
Run sbt compile after each new file. Run sbt test when all files are complete.
Fix all failures before reporting done.

### Reviewer agent
Read the Done When checklist and the ADR.
Verify each checklist item independently.
Run: grep -r "akka" src/ and report output.
Run: sbt test and report pass/fail counts.
Write review output to: docs/phases/phase_1_review.md
Report blocking issues separately from minor observations.

---

## CARRY-OVER TO PHASE 2

Phase 2 reads this section to understand what it inherits. Do not modify these
contracts during Phase 1 — if something was built differently, update the Phase 2
file before starting Phase 2.

```
FILE: src/main/scala/model/Analytics.scala
  HabitContext — Phase 2 ADDS fields, does not redefine or replace
  InsightResponse — unchanged in Phase 2

FILE: src/main/scala/prompt/InsightPrompt.scala
  Phase 2 creates PromptBuilder.scala following the same named-val convention
  InsightPrompt.scala remains unchanged

FILE: src/main/scala/client/AnthropicClient.scala
  Phase 2 reuses without modification
  Phase 2 passes a different system prompt constant — not a change to this file

ENDPOINT: GET /users/{userId}/habits/insights
  Phase 2 adds GET /users/{userId}/habits/analysis alongside it
  Phase 1 endpoint is not modified

PATTERN: prompt = namedConstant + build(ctx) with named section vals
  Phase 2 PromptBuilder must follow this same pattern
```
