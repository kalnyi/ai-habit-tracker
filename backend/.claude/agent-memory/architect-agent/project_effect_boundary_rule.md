---
name: Akka HTTP Future vs cats-effect IO boundary rule (ADR-001)
description: Routes use Future, services/repos use IO, bridge at the route handler via unsafeToFuture() — no Await anywhere
type: project
---

ADR-001 establishes a boundary rule that is enforced by convention in every
route file. The rule is subtle enough that new routes can silently break it:

- **HTTP layer (Akka HTTP `Route`, directives, marshalling):** uses
  `scala.concurrent.Future`. This is Akka HTTP's native idiom.
- **Service and repository layer:** uses `cats.effect.IO` with
  `Either[AppError, A]` for business errors.
- **Bridge:** at each route handler, call `service.foo(...).unsafeToFuture()`
  using the implicit `IORuntime` wired in `Main`. Go the other way with
  `IO.fromFuture(IO(...))` only when unavoidable.
- **No `Await.result` anywhere.** All bridging is non-blocking.

**Why:** CLAUDE.md mandates `cats-effect` / `IO` for business logic, but
Akka HTTP is `Future`-native. Fighting that produces obscure adapters and
unidiomatic code. The boundary-only bridge keeps both sides clean.

**How to apply:** Any new route class the plan introduces must follow the
same pattern `HabitRoutes.scala` uses — `onSuccess(service.xxx(...).unsafeToFuture())`
inside the directive, never `Await`. Any new service-layer API must return
`IO[Either[AppError, A]]`, never `Future`. Flag any plan draft that
accidentally mixes the two.
