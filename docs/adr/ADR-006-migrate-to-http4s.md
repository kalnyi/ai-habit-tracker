## ADR-006 — Migrate HTTP layer from Akka HTTP to http4s
Date: 2026-04-22
Status: Accepted
Author: Human engineer (executed via Claude Code migration task)

### Decision
Replace Akka HTTP 10.5.3 with http4s 0.23.27 (Ember server) as the HTTP layer.

### Context
The existing stack combined Akka HTTP (Future-based) with Doobie (Cats IO-based).
Every route handler required a Future/IO bridge via `unsafeToFuture()` / implicit
`IORuntime`. This caused two concrete problems during agent-driven development:

1. High token usage — every new route required explaining both effect models and
   the bridge pattern to the agent.
2. Wiring bugs — mismatched implicits between Akka HTTP's `ExecutionContext` world
   and Cats Effect's `IORuntime` were a recurring source of compile errors.

Additionally, Akka HTTP 10.6.x and Akka 2.9.x are published only to Lightbend's
BSL-licensed private Maven repository, creating an upgrade ceiling at 10.5.3.

### Consequences
- All async code now uses a single effect model: Cats Effect IO / `F[_]: Async`.
- Route handlers return `HttpRoutes[IO]`; no bridge code, no `unsafeToFuture()`.
- `AppResources.scala` is the single DI wiring point (a `Resource[IO, AppResources]`
  for-comprehension); `Main.scala` is reduced to server startup.
- sttp with the cats-effect backend integrates cleanly for future Anthropic API calls.
- Akka dependencies removed entirely — no `ActorSystem`, no materializer.
- `tapir-http4s-server` + `tapir-swagger-ui-bundle` provide OpenAPI generation.
- Tests use `AsyncWordSpec with AsyncIOSpec` (cats-effect-testing-scalatest) for
  route unit tests; integration tests use `EmberServerBuilder` + Java `HttpClient`.

### Alternatives considered
- **Keep Akka HTTP, wrap Doobie calls in Future** — rejected; increases complexity
  and perpetuates the dual-effect problem.
- **Switch to ZIO HTTP** — rejected; team is learning the Cats Effect ecosystem and
  ZIO would require learning a different typeclass hierarchy.
- **Play Framework + Slick** — rejected; diverges from the existing Doobie investment
  and adds a heavier framework with its own DI model.

### Dependency versions added
```
http4s-ember-server_2.13:0.23.27
http4s-ember-client_2.13:0.23.27
http4s-circe_2.13:0.23.27
http4s-dsl_2.13:0.23.27
sttp.client3:cats_2.13:3.9.7
sttp.client3:circe_2.13:3.9.7
tapir-http4s-server_2.13:1.10.7
tapir-swagger-ui-bundle_2.13:1.10.7
tapir-json-circe_2.13:1.10.7
cats-effect_2.13:3.5.4
```
