# ADR-001: Backend framework — Akka HTTP (engineer override)

## Status
Accepted — supersedes the prior draft that selected http4s.

## Context
The Habit Tracker backend is a Scala 2.13 + Gradle service that needs to expose a
small set of REST endpoints (6 for the initial Habit CRUD, more to follow for
LLM-backed features such as daily tips and pattern analysis). CLAUDE.md mandates
a functional style based on `cats-effect` / `IO`, `Either[AppError, A]` error
handling, immutability, and ScalaTest-based testing.

Three candidates were on the table:

1. **http4s** — a pure-functional HTTP library built on `cats-effect` and `fs2`.
   Small surface area, composable routes as `HttpRoutes[IO]`, decoupled from any
   servlet container. Originally selected by the Architect for maximum alignment
   with the `cats-effect` / `IO` house style.
2. **Play Framework** — a full-stack web framework with an opinionated project
   layout, built-in routing DSL compiled from `routes` files, its own effect
   handling (traditionally `Future`-based), and a larger dependency footprint.
   Rejected early for Gradle awkwardness and PoC overkill.
3. **Akka HTTP** — a mature, widely-used HTTP toolkit built on Akka Streams,
   with a composable `Route` DSL, a clean `Http().newServerAt(...).bind(...)`
   server API, and strong production pedigree across the JVM ecosystem. The
   effect type is `scala.concurrent.Future`.

Selection criteria originally applied:
- Alignment with the `cats-effect` / `IO` house style already declared in CLAUDE.md.
- Gradle-friendliness.
- Minimum surface area for a PoC.
- Testability — unit-testable routes plus integration tests against an ephemeral
  port.

### Engineer override
After reviewing the initial recommendation, the engineer overrode the framework
choice in favour of **Akka HTTP**. The rationale the engineer gave:

- Stronger team familiarity with Akka HTTP from prior projects, lowering the
  onboarding cost for contributors who are not fluent in `cats-effect`.
- Mature, stable DSL with broad community documentation and examples.
- First-class streaming support (via Akka Streams), which is useful for later
  LLM-related features such as streaming responses from the Anthropic API.
- The PoC context makes the `Future` vs `IO` mismatch a manageable trade-off
  rather than a blocker (see "Consequences").

This ADR records that override as the authoritative decision.

## Decision
We will use **Akka HTTP** with the `Http().newServerAt(host, port).bind(route)`
server API, JSON via **akka-http-circe** (Heiko Seeberger's integration module),
and expose routes using the Akka HTTP `Route` DSL. http4s and Play Framework
are rejected for this project.

Concretely:
- HTTP toolkit: `com.typesafe.akka:akka-http_2.13` (10.6.x, latest stable).
- Streams runtime: `com.typesafe.akka:akka-stream_2.13` (2.9.x, the version
  paired with Akka HTTP 10.6.x in the Akka release matrix — to be pinned in
  `build.gradle`).
- JSON: `de.heikoseeberger:akka-http-circe_2.13` plus `circe-core`,
  `circe-generic`, `circe-parser`. Rationale for choosing **akka-http-circe
  over spray-json**:
  - The house JSON library is already Circe (per the original ADR draft and
    the Circe dependencies already listed in the tech plan).
  - `akka-http-circe` is a thin, well-maintained bridge that provides
    `FailFastCirceSupport` for marshalling and unmarshalling — integration is
    straightforward and does not warrant falling back to spray-json.
  - Using spray-json would force a second JSON library into the codebase,
    fragmenting codecs. That outweighs any simplicity advantage from avoiding
    the Seeberger module.
- Route layer effect type: `scala.concurrent.Future` (Akka HTTP's native type).
- Service + repository layer effect type: `cats.effect.IO` (unchanged; still the
  house style, per CLAUDE.md).
- Licensing note: Akka switched to the Business Source Licence (BSL 1.1) from
  Akka 2.7 / Akka HTTP 10.5. For a non-commercial internal PoC this is
  acceptable; if the PoC is ever promoted to a commercial product, the
  licensing must be revisited. Flagging here explicitly.

### Resolving the Future vs IO tension

CLAUDE.md mandates `cats-effect` / `IO` as the house effect type. Akka HTTP is
`Future`-native. Rather than try to force Akka HTTP to speak `IO` end-to-end
(which is possible but adds friction and obscure adapters), we adopt a
**boundary-only bridging** rule:

- **HTTP layer (routes, directives, marshalling):** uses `Future`. This is
  Akka HTTP's native idiom and fighting it produces unidiomatic, hard-to-read
  code.
- **Service layer and below (validation, orchestration, repositories):** uses
  `cats.effect.IO` with `Either[AppError, A]` for business errors, exactly as
  CLAUDE.md requires.
- **Bridge:** at the route handler boundary, call `service.foo(...).unsafeToFuture()`
  (with an implicit `IORuntime` wired in `Main`) to cross from `IO` to `Future`.
  In the rare case we need to go the other way (e.g. wrapping an Akka HTTP
  client call inside a service), use `IO.fromFuture(IO(...))`.
- **No `Await.result` anywhere.** All bridging is non-blocking.

This keeps the `Future` surface confined to the HTTP layer and preserves the
`IO`-based functional style for all business logic. The architect considers
this an acceptable, well-defined boundary rather than a violation of CLAUDE.md.
The rule will be enforced by convention and by `HabitRoutes` never returning
`IO` directly to Akka HTTP.

## Consequences

**Easier:**
- Team members who already know Akka HTTP can contribute immediately without
  a `cats-effect` ramp-up.
- Akka Streams is available for free once we add `akka-stream` — valuable for
  streaming LLM responses in later PBIs.
- The `Route` DSL is declarative and composes cleanly for path prefixes,
  method routing, and error handling via `ExceptionHandler` / `RejectionHandler`.
- Wide ecosystem of examples, blog posts, and Stack Overflow answers.

**Harder / trade-offs:**
- **Dual effect types in the codebase.** `Future` at the HTTP boundary and
  `IO` below it. This is a real cost: developers must consciously bridge at
  the boundary, and testing strategies differ (`ScalaFutures` for routes,
  `cats-effect-testing-scalatest` for services). Mitigated by keeping the
  bridge narrow and well-documented in each `HabitRoutes` method.
- **ActorSystem lifecycle.** Akka HTTP requires a running `ActorSystem` (and
  an implicit `ExecutionContext` derived from it). `Main.scala` must manage
  its creation and graceful shutdown alongside the Doobie `Transactor`.
- **Heavier dependency footprint** than http4s would have been. Akka HTTP +
  Akka Streams pulls in a larger jar set than `http4s-ember-server` alone.
  Acceptable for a PoC.
- **BSL licensing** on Akka 2.7+ / Akka HTTP 10.5+. Fine for an internal PoC;
  must be revisited if the project is ever commercialised.
- Route-level unit tests use Akka HTTP's `ScalatestRouteTest` (a different
  approach from direct `HttpRoutes[IO]` invocation). Still in-process and
  fast; just a different idiom.

**Locked in:**
- HTTP layer effect type is `Future`. Switching later would require rewriting
  every route.
- Service/repository effect type remains `cats-effect` / `IO`. Switching this
  would require rewriting business logic.
- JSON library is Circe, integrated via `akka-http-circe`. Any new endpoint
  should use Circe codecs; introducing a second JSON library later would
  fragment the codebase.
- `ActorSystem` is the top-level runtime alongside `IORuntime`. Both live for
  the full lifetime of the app.
