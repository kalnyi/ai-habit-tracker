---
name: Confirmed tech stack
description: Actual stack in use — supersedes the TBD entries in CLAUDE.md
type: project
---

Backend framework is Akka HTTP 10.5.3 (not http4s), as decided by engineer override in ADR-001. JSON via akka-http-circe + Circe 0.14.9. Effect type: Future at the HTTP layer, cats-effect IO below.

Build command is `gradle test` (no gradlew wrapper — Gradle 9 installed globally).

Frontend framework is still TBD — no frontend PBIs have been written yet.

**Why:** Engineer overrode the initial http4s recommendation citing team familiarity with Akka HTTP and its streaming support for future LLM features.

**How to apply:** Any PBI or ADR flag touching the HTTP layer must reference Akka HTTP, not http4s. Do not suggest http4s-specific libraries.
