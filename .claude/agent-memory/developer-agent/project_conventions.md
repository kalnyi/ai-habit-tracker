---
name: Project conventions and wiring patterns
description: Non-obvious conventions, gotchas, and wiring patterns discovered during PBI-008-017 implementation
type: project
---

## Key wiring patterns

- Phase 3 route order in AppResources: DocsRoutes → InsightsRoutes → AnalysisRoutes → TipsRoutes → BatchCompletionRoutes → HabitRoutes → HabitCompletionRoutes.
- Each analytics/feature endpoint gets its own sibling route class — do NOT add new routes to an existing routes class.
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
- Phase 3 added `retrievedTips: List[String] = Nil` as 8th field on `HabitContext`. Default Nil keeps frozen Phase 1/2 tests compiling.

## Trait + frozen fake service compatibility

- When adding a new abstract method to `HabitCompletionService` (or any trait implemented by a frozen test fake), give the new method a concrete default on the trait (e.g., `IO.raiseError(new NotImplementedError(...))`). This prevents the frozen fake from requiring modification. The real `DefaultHabitCompletionService` still overrides it.

## pgvector / TipRepository

- `TipRepository` uses IO directly, no trait. Doobie has no `Meta[Vector[Float]]`; use the textual literal helper `v.mkString("[", ",", "]")` and cast via `$literal::vector` in SQL.
- `TipRepositorySpec` must use `pgvector/pgvector:pg17` image (not `postgres:17-alpine`) — the vector extension is only available on the pgvector image.
- `TipRepositorySpec` does NOT run Liquibase — it creates the extension and table via raw SQL in `beforeAll`.

## SeedTips / IOApp.Simple

- Use `IO(Source.fromResource(...)).bracket(src => IO(src.getLines().toList))(src => IO(src.close()))` for classpath resource reading inside IO context. Never call `unsafeRunSync()` inside an `IOApp.Simple.run` implementation.
- `cats.syntax.all._` provides `traverse_` on `List`.

## Codec pattern

- `HabitCodecs` holds `ErrorResponse` encoder — import `HabitCodecs._` in any route class that uses `complete(StatusCodes.X, ErrorResponse(...))`.
- `CompletionCodecs` auto-re-derives when `CreateHabitCompletionRequest` / `HabitCompletionResponse` case classes gain new `Option` fields — no manual edit to `CompletionCodecs.scala` needed.
- `AnalyticsCodecs` uses `io.circe.generic.semiauto` — add new response types there; `uuidKeyEncoder`/`uuidKeyDecoder` are already there for `Map[UUID, _]`.

## Scala format string gotcha

- `f"...${score}%+0.2f..."` is invalid — `%+0.2f` requires a width with the `0` flag. Use `%+.2f` (sign + precision, no zero-padding) or `%+7.2f` (sign + width + precision).

## Build quirks

- `scalafmtAll` Gradle task does not exist — there is no `.scalafmt.conf` and the scalafmt Gradle plugin is not configured. No formatter step to run.
- Build output directory is redirected to `LOCALAPPDATA/habit-tracker-build` to avoid OneDrive file-lock issues on Windows.
- Docker tests run in the regular `./gradlew test` but specs carry `@Ignore` — they are skipped (counted as ~58 skipped after Phase 3), not failed.
- New route classes that use `ErrorResponse` (e.g. for BadRequest) must import `HabitCodecs._` in addition to `CompletionCodecs._` — `ErrorResponse` encoder lives in `HabitCodecs`.

## Liquibase changeset path

- Changesets live in `infra/db/changelog/changesets/` and are registered in `infra/db/changelog/db.changelog-master.xml`.
- Testcontainers specs resolve the changelog path via `Paths.get("../infra/db/changelog").toAbsolutePath.normalize` (relative to `backend/` working directory during tests).

## Test helper update pattern

- When a domain case class gains a new field, update `makeCompletion`/`makeHabit` helpers to accept the new param with a default (`completedAt: Option[Instant] = None`). This keeps existing positional call sites compiling while enabling new tests to set the field explicitly.
- Fixture names in ScalaTest must NOT shadow ScalaTest matchers. `empty` is a reserved matcher word — use `emptyCtx` or similar.

**Why:** discovered during PBI-013-014 implementation.
**How to apply:** reference these facts at the start of any future backend session to avoid repeating the same exploratory work.
