# Architect Agent Memory
# Habit Tracker · Agent Swarm
# Last updated: Phase 2 retrospective (2026-04-29)

---

## File Layout

All new source files live under com.habittracker.*:
- LLM composition types  → com.habittracker.model  (HabitContext, InsightResponse, AnalysisResponse)
- External API clients   → com.habittracker.client (AnthropicClient, EmbeddingClient)
- Prompt construction    → com.habittracker.prompt (InsightPrompt, PromptBuilder)
- HTTP route classes     → com.habittracker.http   (InsightsRoutes, AnalysisRoutes)
- Analytical SQL traits  → com.habittracker.repository (AnalyticsRepository)

Phase brief path references like `src/main/scala/model/` map to
`backend/src/main/scala/com/habittracker/model/`. Never create top-level packages
outside com.habittracker.

Established packages as of Phase 2:
  com.habittracker.model      — LLM/analytics composition types
  com.habittracker.client     — external HTTP clients (Anthropic, OpenAI)
  com.habittracker.prompt     — prompt construction objects
  com.habittracker.http       — HTTP route classes

---

## ADR Location and Numbering

ADRs live at `docs/adr/` (NOT `docs/adrs/` — index.md contains an error).
Numbering is sequential across all phases and features:
  ADR-001 through ADR-007: pre-phase work (backend framework, schema, migrations,
                            OpenAPI, completions schema, http4s migration, user domain)
  ADR-008: Phase 1 pattern detection      (docs/adr/ADR-008-phase1-pattern-detection.md)
  ADR-009: Phase 2 habit analysis         (docs/adr/ADR-009-phase2-habit-analysis.md)
  ADR-010: Phase 3 basic RAG              (write to docs/adr/ADR-010-phase3-basic-rag.md)

Phase briefs contain wrong ADR paths (docs/adrs/, ADR-001, ADR-002) — always use
the numbering above. Never write to docs/adrs/.

---

## AnthropicClient Constraints

AnthropicClient (com.habittracker.client.AnthropicClient) is a no-trait,
no-constructor Scala object. Never propose a trait or abstract class around it.
ANTHROPIC_API_KEY is read at object-init; startup failure is forced via:
  Resource.eval(IO(AnthropicClient.API_KEY_CHECK))
in AppResources.make. This file must not be modified in Phase 3 or beyond.
Phase 2 reuses it by passing PromptBuilder.HABIT_COACH_SYSTEM_PROMPT directly
to AnthropicClient.complete[IO](...).

EmbeddingClient (Phase 3) must follow the exact same pattern as AnthropicClient:
no trait, no constructor, direct sttp call visible at call site, API key from
environment forced at startup.

---

## Build Tool

Build tool is Gradle (./gradlew test, ./gradlew compileScala).
Phase briefs say "sbt" in their STACK and Done When sections — this is
documentation drift. All agent instructions and plans must use ./gradlew.

---

## Route Registration Pattern

Routes compose in AppResources.make using <+>:
  DocsRoutes → InsightsRoutes → AnalysisRoutes → HabitRoutes → HabitCompletionRoutes

Phase 2 inserted AnalysisRoutes between InsightsRoutes and HabitRoutes.
Phase 3 adds TipsRoutes by appending to this chain after AnalysisRoutes.
Do not alter existing route order.

---

## Effect Type Convention

Route classes use IO directly (HttpRoutes[IO]), not F[_]. Service traits return
IO[_], not F[_]: Async. AnthropicClient.complete is polymorphic (F[_]: Async)
but is always called as AnthropicClient.complete[IO](...) in route handlers.
EmbeddingClient.embed must follow the same pattern.
TipRepository must use IO directly, not F[_]: Async — phase brief has this wrong.
Do not propose F[_] in route, service, or repository signatures.

---

## User-Scoping Rule (from ADR-007 and ADR-008)

HabitRepository enforces `AND user_id = $userId` on every SELECT.
AnalyticsRepository.streakForHabit(habitId: UUID) is scoped by habitId only —
the caller (AnalyticsService) must call HabitRepository.listActive(userId) first
to verify ownership. This deliberate divergence is documented in AnalyticsRepository
scaladoc and must be maintained in Phase 3.

---

## HabitContext Defaults Pattern (from Phase 2)

When a new field must be added to HabitContext and a frozen test constructs it
with fewer arguments (HARD LIMIT — cannot modify frozen tests), add a Scala
default value to the new field:
  newField: Map[UUID, Double] = Map.empty
  newField: List[(String, String)] = Nil

This is safe because the sole production construction (DefaultAnalyticsService)
always supplies all fields explicitly by name. The default is never triggered
in production. The OpenAPI spec still marks all fields as `required`.
Apply this pattern for Phase 3's `retrievedTips: List[String] = Nil`.

---

## Known Accepted Trade-offs (from ADR-008)

1. InsightsRoutes and AnalysisRoutes have no unit test — AnthropicClient
   is not stubbable per HARD LIMIT. This is expected, not a defect.
2. ANTHROPIC_API_KEY startup failure is verified manually only.
3. application.conf and logback.xml contain legacy akka config entries from the
   http4s migration. No Scala akka imports exist. Not a blocking issue.
