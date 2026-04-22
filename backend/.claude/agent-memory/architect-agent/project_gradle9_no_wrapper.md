---
name: Gradle 9 with no wrapper; many Gradle plugins are incompatible
description: Build uses system Gradle 9.4.1; several ecosystem plugins (Flyway 10, Liquibase 3.x) broke on Gradle 9 and required workarounds
type: project
---

The backend uses system Gradle 9.4.1 directly — there is no `gradlew` wrapper
pinned. Developers run `gradle <task>`.

**Why it matters for planning:** Gradle 9 removed several APIs that older
plugins reach into. Two known casualties so far:

- The Flyway Gradle plugin 10.17.0 is incompatible with Gradle 9 — this is
  the origin story for ADR-003 (swap to Liquibase).
- The `org.liquibase.gradle` plugin 3.x (the documented CLI wrapper for
  Liquibase) also broke on Gradle 9 — `LiquibaseTask.exec()` was removed.
  The project works around this with a hand-rolled `JavaExec` task named
  `update` in `build.gradle`, driving the Liquibase CLI (`LiquibaseCommandLine`)
  on a separate `liquibaseRuntime` configuration.

**How to apply:** When a plan proposes a new Gradle plugin, verify Gradle 9
compatibility explicitly (check the plugin's release notes or issue tracker
for "Gradle 9"). If compatibility is unclear or broken, fall back to a
`JavaExec` or `Exec` task that shells out to the tool's own CLI — this is
the established escape hatch in this project. Do not propose downgrading
Gradle.
