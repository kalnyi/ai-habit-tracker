---
name: http4s review criteria (post-migration)
description: Review checklist items specific to the http4s stack after Akka HTTP removal
type: project
---

After the Akka HTTP → http4s migration (ADR-006, 2026-04-22), the following are
hard review criteria:

**Route return type:** Must be `HttpRoutes[IO]` (or `HttpRoutes[F]` with type param). Any PR
returning `akka.http.scaladsl.server.Route` is a blocker.

**No Future in route handlers or services.** The only permitted Future usage would be
inside library code. Service methods must return `IO[Either[AppError, A]]`. Flag any
`unsafeToFuture()`, `Await.result`, or `.future` bridge call.

**No akka imports anywhere in `src/`.** grep for `akka` in scala files; any hit is a blocker.

**Test base class for route specs:** `AsyncWordSpec with AsyncIOSpec with Matchers`
(from cats-effect-testing-scalatest). Do NOT use `ScalatestRouteTest` (Akka).
Integration specs use `AnyWordSpec with ... with ScalaFutures` + Ember server + Java HttpClient.

**`resp.as[String]` in route tests is a bug.** When `CirceEntityCodec._` is imported,
`as[String]` uses Circe and fails on JSON objects. Correct idiom: `resp.bodyText.compile.string`.

**Content-Type header assertions:** In tests, `Content-Type.toString()` renders with the type prefix.
Use `.contains(...)` not `.startsWith(...)` when asserting on `_.toString()`. Or use
`_.mediaType.toString()` (Renderable gives clean "type/subtype" string) with `.startsWith`.

**DI:** All wiring must go through `AppResources.scala`. No ad-hoc transactor or service
construction in Main.scala.
