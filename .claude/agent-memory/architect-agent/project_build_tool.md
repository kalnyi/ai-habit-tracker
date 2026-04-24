---
name: Build tool is Gradle, not sbt
description: All build, compile, and test commands use ./gradlew. Phase briefs sometimes reference sbt — treat that as documentation drift and note the correction in the ADR.
type: project
---

The backend uses Gradle (`./gradlew`) with the Scala plugin. There is no
sbt build definition. Commands throughout the repo are `./gradlew test`,
`./gradlew compileScala`, `./gradlew run`.

**Why:** phase briefs (e.g. `docs/phases/phase_1_pattern_detection.md`)
sometimes reference `sbt test` in their STACK section. This is
documentation drift from an earlier planning stage; the actual
implementation is Gradle. The engineer has flagged this as a recurring
correction point — every plan/ADR that references the brief must restate
the Gradle reality for the Developer agent.

**How to apply:** in every ADR and PLAN, include a short correction note
("Build tool is Gradle, not sbt — the brief's sbt references are
documentation drift") so the Developer agent does not try to run `sbt`.
Use `./gradlew` verbatim in any command listed in the plan.
