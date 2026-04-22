---
name: Project conventions and wiring patterns
description: Non-obvious conventions, gotchas, and wiring patterns discovered during PBI-008-011 implementation
type: project
---

## Key wiring patterns

- Routes are concatenated in `Main.scala` with `~` in the order: `docsRoutes.route ~ habitRoutes.route ~ completionRoutes.route`. The `docsRoutes` must come first (existing commit note explains why).
- `HabitCompletionRoutes` owns its own `pathPrefix("habits")` subtree (split ownership). The existing `path(Segment)` fallback in `HabitRoutes` only matches single-segment paths so it does not conflict with multi-segment completion paths.
- `ErrorHandler.toRoute` is a `match` on the sealed `AppError` hierarchy — adding a new variant (`ConflictError`) requires adding a new `case` branch there.

## Doobie UNIQUE_VIOLATION handling

- Use `doobie.util.catchsql.attemptSomeSqlState[IO, Int, ConflictError](insertIO)` with `sqlstate.class23.UNIQUE_VIOLATION` from `doobie.postgres.sqlstate`.
- The `doobie.syntax.applicativeerror._` extension method `exceptSomeSqlState` does NOT work directly on `IO[Either[_, _]]` — use the `catchsql` object functions instead.
- `attemptSomeSqlState` returns `IO[Either[B, A]]` where B is the error type you provide in the partial function — then `.map` to convert `Either[ConflictError, Int]` to `Either[ConflictError, Unit]`.

## Codec pattern

- `HabitCodecs` holds `ErrorResponse` encoder — import `HabitCodecs._` in any route class that uses `complete(StatusCodes.X, ErrorResponse(...))`.
- `CompletionCodecs` builds on top of `HabitCodecs` (imports `uuidEncoder`, `instantEncoder`, etc.) via explicit import.
- `LocalDate` codec: hand-written using `Encoder.encodeString.contramap(_.toString)` and `Decoder.decodeString.emap(s => Try(LocalDate.parse(s)).toEither.left.map(_.getMessage))`.

## Build quirks

- `scalafmtAll` Gradle task does not exist — there is no `.scalafmt.conf` and the scalafmt Gradle plugin is not configured. No formatter step to run.
- Build output directory is redirected to `LOCALAPPDATA/habit-tracker-build` to avoid OneDrive file-lock issues on Windows.
- Docker tests are excluded with `-PskipDockerTests` — the `test` task excludes `com/habittracker/repository/**` and `com/habittracker/integration/**` in that mode.

## Liquibase changeset path

- Changesets live in `infra/db/changelog/changesets/` and are registered in `infra/db/changelog/db.changelog-master.xml`.
- Testcontainers specs resolve the changelog path via `Paths.get("../../infra/db/changelog").toAbsolutePath.normalize` (relative to the `backend/` working directory during tests).

**Why:** discovered during PBI-008-011 implementation.
**How to apply:** reference these facts at the start of any future backend session to avoid repeating the same exploratory work.
