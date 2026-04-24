---
name: Testcontainers specs are @Ignore'd and run manually with Docker
description: Every Doobie*Spec and integration spec in backend/ carries @Ignore; the engineer removes the annotation locally when Docker is running. New DB-integration specs must follow the same pattern.
type: project
---

Every repository spec that needs a running Postgres (e.g.
`DoobieHabitRepositorySpec`, `DoobieHabitCompletionRepositorySpec`) and
the HTTP integration specs (`HabitApiIntegrationSpec`,
`HabitCompletionApiIntegrationSpec`) carry `@Ignore` at the class level so
that `./gradlew test` in CI-without-Docker passes cleanly. The engineer
runs them manually by toggling `@Ignore` off or by running the single
class from the IDE.

**Why:** CI does not provision Docker; the team still wants repository
specs under version control for local correctness checks. Leaving them
active would break the headless `./gradlew test` contract that other
agents (Developer, Reviewer) rely on to declare "tests pass".

**How to apply:** any new Testcontainers-driven spec gets the same
annotations pattern seen in `DoobieHabitRepositorySpec`:
`@Ignore`, `@RunWith(classOf[JUnitRunner])`, and Liquibase bootstrap via
`Paths.get("../../infra/db/changelog").toAbsolutePath.normalize`. Pure
unit tests (no Docker) do **not** get `@Ignore` — they run in CI and must
pass headlessly.
