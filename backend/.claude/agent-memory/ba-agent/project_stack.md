---
name: Confirmed tech stack
description: Actual stack in use — supersedes the TBD entries in CLAUDE.md and earlier Akka HTTP entries
type: project
---

The project migrated from Akka HTTP to http4s before Phase 1 (ADR-006). The current confirmed stack is:

- HTTP framework: http4s 0.23.27 + Ember server (not Akka HTTP)
- Effects: Cats Effect 3 — routes and services use IO directly, not Future
- Database: Doobie + PostgreSQL via Docker Compose
- JSON: Circe via http4s-circe; codecs use io.circe.generic.semiauto (not auto) in dedicated codecs files
- HTTP client: sttp with cats-effect backend
- Testing: ScalaTest AnyWordSpec + @RunWith(classOf[JUnitRunner]) (NOT munit-cats-effect/CatsEffectSuite)
- Build: Gradle with gradlew wrapper (./gradlew test, ./gradlew compileScala) — Gradle is the build tool; phase briefs may say "sbt" but that is documentation drift
- LLM: Claude via Anthropic API, model claude-sonnet-4-20250514
- Frontend: TBD (no frontend PBIs written yet)

**Why:** Engineer decided to migrate to http4s before Phase 1 (ADR-006). Earlier memory entry reflected the pre-migration state and is now obsolete.

**How to apply:** Any PBI or ADR flag touching the HTTP layer must reference http4s, not Akka HTTP. Do not suggest Akka HTTP, akka-streams, or akka-http-circe. Phase brief STACK sections that say "sbt" as the build tool are wrong — always use ./gradlew.
