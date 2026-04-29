---
name: Project conventions and wiring patterns
description: Non-obvious conventions, gotchas, and wiring patterns discovered during PBI-008-014 implementation
type: project
---

## Key wiring patterns

- Routes are concatenated in `AppResources.scala` with `<+>` (SemigroupK) in the order: DocsRoutes, InsightsRoutes, AnalysisRoutes, HabitRoutes, HabitCompletionRoutes. DocsRoutes must come first.
- Each analytics endpoint gets its own sibling route class (InsightsRoutes, AnalysisRoutes) — do NOT add new routes to an existing routes class.
- `AnalyticsService.buildHabitContext` uses `parTupled` for the 4 user-scoped aggregates and `parTraverse` for per-habit fan-outs. This pattern is locked by ADR-009.

## Doobie UNIQUE_VIOLATION handling

- Use `doobie.util.catchsql.attemptSomeSqlState[IO, Int, ConflictError](insertIO)` with `sqlstate.class23.UNIQUE_VIOLATION` from `doobie.postgres.sqlstate`.
- The `doobie.syntax.applicativeerror._` extension method `exceptSomeSqlState` does NOT work directly on `IO[Either[_, _]]` — use the `catchsql` object functions instead.
- `attemptSomeSqlState` returns `IO[Either[B, A]]` where B is the error type you provide in the partial function — then `.map` to convert `Either[ConflictError, Int]` to `Either[ConflictError, Unit]`.

## Domain model evolution — HabitContext

- `HabitContext` lives in `com.habittracker.model.Analytics`. Three Phase 1 fields + three Phase 2 fields. Phase 3 will add `retrievedTips`.
- The Phase 2 fields (`timeOfDayPatterns`, `correlatedPairs`, `momentumScores`) have **default values** (`Map.empty`, `Nil`, `Map.empty`) to keep `InsightPromptSpec` (a HARD LIMIT) compilable when it constructs `HabitContext` with only 4 args.
- `HabitCompletion` gains `completedAt: Option[Instant]` appended at the end — do NOT insert in the middle.
- `habitId` keys in all Maps are `UUID` (not `Long`) — confirmed in ADR-009 §4.

## Codec pattern

- `HabitCodecs` holds `ErrorResponse` encoder — import `HabitCodecs._` in any route class that uses `complete(StatusCodes.X, ErrorResponse(...))`.
- `CompletionCodecs` auto-re-derives when `CreateHabitCompletionRequest` / `HabitCompletionResponse` case classes gain new `Option` fields — no manual edit to `CompletionCodecs.scala` needed.
- `AnalyticsCodecs` uses `io.circe.generic.semiauto` — add new response types there; `uuidKeyEncoder`/`uuidKeyDecoder` are already there for `Map[UUID, _]`.

## Scala format string gotcha

- `f"...${score}%+0.2f..."` is invalid — `%+0.2f` requires a width with the `0` flag. Use `%+.2f` (sign + precision, no zero-padding) or `%+7.2f` (sign + width + precision).

## Build quirks

- `scalafmtAll` Gradle task does not exist — there is no `.scalafmt.conf` and the scalafmt Gradle plugin is not configured. No formatter step to run.
- Build output directory is redirected to `LOCALAPPDATA/habit-tracker-build` to avoid OneDrive file-lock issues on Windows.
- Docker tests run in the regular `./gradlew test` but specs carry `@Ignore` — they are skipped (counted as 51 skipped), not failed.

## Liquibase changeset path

- Changesets live in `infra/db/changelog/changesets/` and are registered in `infra/db/changelog/db.changelog-master.xml`.
- Testcontainers specs resolve the changelog path via `Paths.get("../infra/db/changelog").toAbsolutePath.normalize` (relative to `backend/` working directory during tests).

## Test helper update pattern

- When a domain case class gains a new field, update `makeCompletion`/`makeHabit` helpers to accept the new param with a default (`completedAt: Option[Instant] = None`). This keeps existing positional call sites compiling while enabling new tests to set the field explicitly.
- Fixture names in ScalaTest must NOT shadow ScalaTest matchers. `empty` is a reserved matcher word — use `emptyCtx` or similar.

**Why:** discovered during PBI-013-014 implementation.
**How to apply:** reference these facts at the start of any future backend session to avoid repeating the same exploratory work.
