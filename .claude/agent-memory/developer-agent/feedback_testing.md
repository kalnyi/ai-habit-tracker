---
name: Test patterns and shared helpers
description: Test double conventions, shared helpers, and what to reuse in future specs
type: feedback
---

## Shared test helpers

- `backend/src/test/scala/com/habittracker/repository/InMemoryHabitRepository.scala` — shared in-memory `HabitRepository` double. Use this in any spec that needs habits without Docker. Do NOT copy-paste it.
- The fake clock pattern (`Clock[IO]` with `@volatile var current`) is defined in both `HabitServiceSpec` and `HabitCompletionServiceSpec`. Consider lifting it into a shared `TestClock` helper if a third spec needs it.

## Test class structure

- Unit test specs extend `AnyWordSpec with Matchers`.
- Async IO specs extend `AsyncWordSpec with AsyncIOSpec with Matchers`.
- Route specs extend `AnyWordSpec with Matchers with ScalatestRouteTest`.
- All specs annotated with `@RunWith(classOf[JUnitRunner])`.
- Docker-dependent specs additionally annotated with `@Ignore`.

## Fake service pattern (routes tests)

- `FakeHabitCompletionService` in `HabitCompletionRoutesSpec` takes `IO[Either[AppError, X]]` as constructor args with defaults. Mirrors `FakeHabitService` in `HabitRoutesSpec`.
- Each test overrides only the result it cares about: `new FakeHabitCompletionService(recordResult = IO.pure(Left(NotFound(...))))`.

## What to verify before declaring done

1. `./gradlew test -PskipDockerTests` — all unit tests pass.
2. No compiler errors (warnings about `Block result was adapted via implicit conversion` are pre-existing and benign).
3. `@Ignore` on any Testcontainers spec.

**Why:** approved by engineer decision 4 (lift into shared helper, not copy-paste). Route spec pattern is established convention across all existing specs.
**How to apply:** always check for existing helpers before writing new fakes or doubles.
