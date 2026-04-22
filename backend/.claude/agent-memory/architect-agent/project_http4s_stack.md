---
name: http4s stack replaces Akka HTTP — CE3 coherence migration
description: HTTP layer is http4s 0.23.27 (Ember server); all async code uses Cats Effect IO; no Future/IO bridge needed
type: project
---

ADR-006 (2026-04-22) records the migration from Akka HTTP to http4s.

**Stack versions (build.gradle ext block):**
- `http4sVersion = '0.23.27'`
- `tapirVersion = '1.10.7'`
- `cats-effect_2.13:3.5.4`
- `sttp.client3:cats_2.13:3.9.7` (for downstream HTTP / Anthropic API calls)

**Why:** Akka HTTP is Future-based; Doobie and the rest of the service layer use
Cats Effect IO. Every route handler needed an unsafeToFuture() bridge that increased
token usage and introduced subtle wiring bugs. A single IO effect model removes
the bridge entirely.

**Architectural pattern — AppResources:**
`backend/src/main/scala/com/habittracker/AppResources.scala` is the single DI
wiring point. It is a `Resource[IO, AppResources]` for-comprehension that builds
the transactor, repositories, services, and combined HttpRoutes. `Main.scala` calls
`AppResources.make.use(...)` and starts the Ember server.

**Effect model rule (supersedes project_effect_boundary_rule.md):**
All async code — routes, services, repos, DI wiring — returns `IO[A]` or
`F[_]: Async`. No `Future` anywhere. No `unsafeToFuture()`. The only
`ExecutionContext` is inside `DatabaseConfig` for the Hikari connection pool.

**How to apply:** When planning new endpoints or services, return `IO[Either[AppError, A]]`
from services and `HttpRoutes[IO]` from route classes. Never introduce Future or
bridge code. Any new downstream HTTP call uses sttp with the cats-effect backend.
