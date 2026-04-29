# Architect Agent Memory
# Habit Tracker · Agent Swarm
# Last updated: Phase 4 retrospective (2026-04-30)

---

## File Layout

All new source files live under com.habittracker.*:
- LLM composition types    → com.habittracker.model        (HabitContext, InsightResponse, AnalysisResponse, TipsResponse, UserNote, NoteRequest, etc.)
- External API clients     → com.habittracker.client       (AnthropicClient, EmbeddingClient)
- Prompt construction      → com.habittracker.prompt       (InsightPrompt, PromptBuilder)
- HTTP route classes       → com.habittracker.http         (InsightsRoutes, AnalysisRoutes, TipsRoutes, BatchCompletionRoutes, NoteRoutes)
- Analytical SQL traits    → com.habittracker.repository   (AnalyticsRepository, TipRepository, NoteRepository)
- Pure service logic       → com.habittracker.service      (AnalyticsService, HabitCompletionService, Deduplication)
- Observability            → com.habittracker.observability (RagLogger)

Phase brief path references like `src/main/scala/model/` map to
`backend/src/main/scala/com/habittracker/model/`. Never create top-level packages
outside com.habittracker.

---

## ADR Location and Numbering

ADRs live at `docs/adr/` (NOT `docs/adrs/` — index.md contains an error).
Numbering is sequential:
  ADR-001 through ADR-007: pre-phase work
  ADR-008: Phase 1 — docs/adr/ADR-008-phase1-pattern-detection.md
  ADR-009: Phase 2 — docs/adr/ADR-009-phase2-habit-analysis.md
  ADR-010: Phase 3 — docs/adr/ADR-010-phase3-basic-rag.md
  ADR-011: Phase 4 — docs/adr/ADR-011-phase4-full-rag.md
  ADR-012: Phase 5 — write to docs/adr/ADR-012-phase5-*.md

Phase briefs contain wrong ADR paths (docs/adrs/, ADR-001 through ADR-004) — always
use the numbering above. Never write to docs/adrs/.

---

## Client Constraints (AnthropicClient and EmbeddingClient)

Both are frozen no-trait, no-constructor Scala objects. Never modify them.
API keys read at object-init; startup failure forced via Resource.eval in AppResources.
Any future HTTP client must follow the same sttp pattern. See Developer MEMORY for
the critical content-type header rule.

---

## Build Tool

Gradle (./gradlew test, ./gradlew compileScala). Phase briefs say "sbt" — ignore.

---

## Route Registration Pattern

Current route composition in AppResources.make:
  DocsRoutes → InsightsRoutes → AnalysisRoutes → TipsRoutes → BatchCompletionRoutes
  → NoteRoutes → HabitRoutes → HabitCompletionRoutes

Append new routes after NoteRoutes and before HabitRoutes.

---

## pgvector Setup Boundary (from ADR-010)

pgvector tables live in infra/db/init/*.sql (Docker init scripts), NOT Liquibase.
  01_enable_vector.sql — CREATE EXTENSION vector
  02_create_habit_tips.sql — habit_tips table
  03_create_user_notes.sql — user_notes table
Liquibase manages application tables only (habits, habit_completions, users).
Next free Liquibase slot: 006.
Wire format: hand-rolled `[v1,v2,...]` string cast via `::vector`. No pgvector-java.

---

## Effect Type Convention

Route classes: IO directly (HttpRoutes[IO]). Service traits: IO[_]. Repository classes: IO.
Phase briefs show F[_]: Async for NoteRepository/TipRepository — wrong, use IO.
AnthropicClient.complete and EmbeddingClient.embed are polymorphic (F[_]: Async)
but always called as .complete[IO] / .embed[IO] at call sites.

---

## User-Scoping Rule

HabitRepository: AND user_id = $userId on every SELECT.
NoteRepository.findSimilar: must filter by userId — enforced and tested (NoteRepositorySpec
two-user test). Any future user-scoped repository must follow this pattern.

---

## HabitContext Defaults Pattern

New fields use Scala defaults when frozen tests construct HabitContext with fewer args.
Never triggered in production (DefaultAnalyticsService supplies all fields by name).
  Phase 2: timeOfDayPatterns, correlatedPairs, momentumScores
  Phase 3: retrievedTips: List[String] = Nil

---

## Service Trait New-Method Pattern

New methods on service traits: concrete default `IO.raiseError(new NotImplementedError(...))`
— never abstract. Prevents breaking frozen test fakes. Real impl overrides it.

---

## Known Accepted Trade-offs

1. No unit tests for route handlers that call live API clients (InsightsRoutes, AnalysisRoutes,
   TipsRoutes, NoteRoutes). Expected — clients not stubbable per HARD LIMIT.
2. ANTHROPIC_API_KEY and OPENAI_API_KEY startup failure paths verified manually only.
3. Legacy akka config entries in application.conf and logback.xml. Pre-existing, not a defect.
4. SeedTipsIdempotencySpec tests TipRepository directly, not SeedTips.run.
5. Eval endpoint deferred — removed in Phase 4 BA phase. Documented in ADR-011 §7 and
   docs/future_improvements.md for future implementation with proper labelled test sets.
