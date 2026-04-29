---
name: Codebase conventions discovered during reviews
description: Conventions observed in the actual code that are not yet fully documented in CLAUDE.md
type: project
---

## var in test infrastructure is accepted
`private var` fields are used in Testcontainers-based specs (`DoobieHabitRepositorySpec`, `DoobieHabitCompletionRepositorySpec`, integration specs) for lifecycle fields like `transactor`, `system`, `binding`. This is consistent with ScalaTest's `BeforeAndAfterAll` lifecycle model and is an established project pattern. Do not flag these as violations — only flag `var` in production (main) source.

## @Ignore is the convention for Docker-dependent tests
Tests that require Docker (Testcontainers repository specs, integration API specs) are annotated `@Ignore`. This is the agreed pattern for CI — they are meant to be run manually. The test files must exist with meaningful test bodies; simply being `@Ignore`d satisfies the "integration test exists" acceptance criteria in PBIs.

## Multiple PBIs may be batched in one commit
CLAUDE.md says each agent stage should produce a separate commit, but PBI-008 through PBI-011 were all delivered in one commit (`f791483`). The engineer accepted this. Do not treat single-commit multi-PBI delivery as a blocking issue — note it as a workflow observation only.

## Liquibase changeset author uses "habit-tracker" alias
Both changesets (001 and 002) use `habit-tracker` as the Liquibase changeset author field. This is the established project convention, not a defect.

## InMemoryHabitRepository lives in test source
`InMemoryHabitRepository` was lifted from `HabitServiceSpec` to `backend/src/test/scala/com/habittracker/repository/InMemoryHabitRepository.scala` so it can be shared across service tests. This is the correct pattern for test doubles — they live in `src/test`, not `src/main`.

## Default values on case class fields as compile-time HARD LIMIT shim
When a frozen Phase N test constructs a case class that gains new required fields in Phase N+1, adding Scala default values to the new fields is the accepted workaround. Reviewed and approved in Phase 2 (HabitContext Phase 2 fields). The key acceptance test: (a) production code must always supply all fields explicitly — never rely on the defaults at runtime; (b) the default values must represent safe "empty" states (Map.empty, Nil, 0.0) so that if a default is accidentally triggered the behaviour is deterministic and visible. Flag any default value that is non-trivial (e.g. a computed value, a clock read, a non-empty collection) as a blocking issue.

## HabitCompletionResponse test coverage gap (Phase 2, still open in Phase 3)
The HabitCompletionCodecsSpec does not include a round-trip test for HabitCompletionResponse with completedAt populated (non-None). Phase 3 added completedAt coverage for CreateHabitCompletionRequest but the response-side gap remains. Flag in future completion-codec reviews.

## BatchCompletionResponse codec tests missing (Phase 3)
BatchCompletionResponse and SkippedCompletion codecs added in Phase 3 are tested only through the integration spec (@Ignore), not by unit-level round-trip tests in HabitCompletionCodecsSpec. Flag as a warning in any future review that touches these codecs.

## SeedTipsIdempotencySpec exercises TipRepository, not SeedTips.run
The spec annotated @Ignore under `scripts/` package verifies the SELECT-then-INSERT idempotency mechanism via TipRepository directly, using dummy embeddings. SeedTips.run itself is not called. This is accepted (live API key required) but a future reader may be confused by the mismatch between the test name and its scope.

## docker-compose.yaml is at project root, not infra/
CLAUDE.md references `infra/docker-compose.yml` but the actual file is `docker-compose.yaml` at the repository root. Pre-existing before Phase 3. Do not flag as a Phase 3 issue.

## processOne fold is type-safe in batch service
In DefaultHabitCompletionService.processOne, the fold first-arg `{ case ConflictError(msg) => ... }` looks like a partial function but is exhaustive because DoobieHabitCompletionRepository.create returns IO[Either[ConflictError, Unit]] — the Left type is ConflictError specifically, not the broader AppError. The Scala compiler does not require a wildcard here. Do not flag as a non-exhaustive pattern match.
