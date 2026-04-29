# Architect Agent Memory
# Habit Tracker · Agent Swarm
# Last updated: Phase 3 retrospective (2026-04-29)

---

## File Layout

All new source files live under com.habittracker.*:
- LLM composition types    → com.habittracker.model        (HabitContext, InsightResponse, AnalysisResponse, TipsResponse, etc.)
- External API clients     → com.habittracker.client       (AnthropicClient, EmbeddingClient)
- Prompt construction      → com.habittracker.prompt       (InsightPrompt, PromptBuilder)
- HTTP route classes       → com.habittracker.http         (InsightsRoutes, AnalysisRoutes, TipsRoutes, BatchCompletionRoutes)
- Analytical SQL traits    → com.habittracker.repository   (AnalyticsRepository, TipRepository, NoteRepository)
- Pure service logic       → com.habittracker.service      (AnalyticsService, HabitCompletionService, Deduplication)
- Observability            → com.habittracker.observability (RagLogger)

Phase brief path references like `src/main/scala/model/` map to
`backend/src/main/scala/com/habittracker/model/`. Never create top-level packages
outside com.habittracker.

---

## ADR Location and Numbering

ADRs live at `docs/adr/` (NOT `docs/adrs/` — index.md contains an error).
Numbering is sequential across all phases:
  ADR-001 through ADR-007: pre-phase work
  ADR-008: Phase 1 pattern detection      (docs/adr/ADR-008-phase1-pattern-detection.md)
  ADR-009: Phase 2 habit analysis         (docs/adr/ADR-009-phase2-habit-analysis.md)
  ADR-010: Phase 3 basic RAG              (docs/adr/ADR-010-phase3-basic-rag.md)
  ADR-011: Phase 4 full RAG               (write to docs/adr/ADR-011-phase4-full-rag.md)

Phase briefs contain wrong ADR paths (docs/adrs/, ADR-001 through ADR-004) — always use
the numbering above. Never write to docs/adrs/.

---

## AnthropicClient and EmbeddingClient Constraints

Both are no-trait, no-constructor Scala objects. Never propose a trait or abstract class
around either. API keys are read at object-init; startup failure is forced via:
  Resource.eval(IO(AnthropicClient.API_KEY_CHECK))
  Resource.eval(IO(EmbeddingClient.API_KEY_CHECK))
in AppResources.make. Neither file must be modified in Phase 4 or beyond.

Any future HTTP client (Phase 4 and beyond) must follow the exact same sttp pattern.
See Developer MEMORY for the critical content-type header rule.

---

## Build Tool

Build tool is Gradle (./gradlew test, ./gradlew compileScala).
Phase briefs say "sbt" in their STACK and Done When sections — documentation drift.
All agent instructions and plans must use ./gradlew.

---

## Route Registration Pattern

Current route composition in AppResources.make (do not break this order):
  DocsRoutes → InsightsRoutes → AnalysisRoutes → TipsRoutes → BatchCompletionRoutes
  → HabitRoutes → HabitCompletionRoutes

Phase 4 adds NoteRoutes (and possibly an evaluate route inside TipsRoutes or a new class).
Phase 4 also MODIFIES TipsRoutes (parallel retrieval replaces sequential) — TipsRoutes is
NOT frozen in Phase 4. Do not alter existing route order; append new routes after
BatchCompletionRoutes and before HabitRoutes.

---

## pgvector Setup Boundary (from ADR-010)

pgvector setup is INFRASTRUCTURE, not application schema:
- CREATE EXTENSION vector + corpus/note tables (habit_tips, user_notes)
  live in infra/db/init/*.sql, mounted into Postgres via docker-compose.yaml.
- Liquibase changelog manages application tables only (habits, habit_completions, users).
  The next free Liquibase slot is 006, reserved for genuine application schema changes.
- Wire format: hand-rolled textual literal `[v1,v2,...]` cast via `::vector` in SQL.
  No com.pgvector:pgvector-java dependency.
- Read-side: never SELECT the embedding column back as Vector[Float]; only the
  1.0 - cosine_distance score is projected.

---

## Embedding Wire Format (from ADR-010)

EmbeddingClient response shape: OpenAI returns `data[0].embedding` as an array of floats.
Parse with `json.hcursor.downField("data").downArray.downField("embedding").as[Vector[Float]]`.
Validate `vec.size == DIMENSION (1536)` and raise on mismatch.
OpenAI auth header is `Authorization: Bearer <key>`, not `x-api-key`.

---

## Effect Type Convention

Route classes use IO directly (HttpRoutes[IO]), not F[_]. Service traits return IO[_],
not F[_]: Async. All repository classes use IO directly, not F[_]: Async.
Phase briefs have `NoteRepository[F[_]: Async]` — this is wrong, use IO.
AnthropicClient.complete and EmbeddingClient.embed are polymorphic (F[_]: Async)
but are always called as `.complete[IO]` / `.embed[IO]` at route call sites.
Do not propose F[_] in route, service, or repository signatures.

---

## User-Scoping Rule (from ADR-007 and ADR-008)

HabitRepository enforces `AND user_id = $userId` on every SELECT.
NoteRepository (Phase 4) must also filter by userId in findSimilar — must not return
other users' notes. The Reviewer will specifically verify this with a dedicated test.

---

## HabitContext Defaults Pattern

When adding new fields to HabitContext where a frozen test constructs it with fewer args,
add a Scala default value. Never triggered in production (DefaultAnalyticsService always
supplies all fields explicitly). OpenAPI spec marks all fields as required.
  Phase 2: timeOfDayPatterns = Map.empty, correlatedPairs = Nil, momentumScores = Map.empty
  Phase 3: retrievedTips: List[String] = Nil

---

## Service Trait New-Method Pattern (from Phase 3)

When adding a new method to a service trait where a frozen test fake extends the trait,
use a concrete default `IO.raiseError(new NotImplementedError(...))` on the trait —
do NOT make it abstract. The real implementation overrides it. This avoids breaking
frozen fakes (e.g. FakeHabitCompletionService in HabitCompletionRoutesSpec).

---

## Known Accepted Trade-offs

1. InsightsRoutes, AnalysisRoutes, TipsRoutes have no unit test — live API clients
   are called directly at the route call site per HARD LIMIT. Expected, not a defect.
2. ANTHROPIC_API_KEY and OPENAI_API_KEY startup failure paths verified manually only.
3. application.conf and logback.xml contain legacy akka config entries. Not a defect.
4. SeedTipsIdempotencySpec tests TipRepository directly, not SeedTips.run itself.
   Accepted under the live-API @Ignore trade-off.
