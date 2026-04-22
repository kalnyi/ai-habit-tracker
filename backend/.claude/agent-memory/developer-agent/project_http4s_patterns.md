---
name: http4s route and testing patterns
description: Canonical patterns for routes, JSON, testing, and DI wiring after the Akka HTTP → http4s migration
type: project
---

**HTTP framework:** http4s 0.23.27 with Ember server (EmberServerBuilder).

**Route class pattern:**
```scala
final class HabitRoutes(service: HabitService) {
  private object UUIDVar { def unapply(s: String): Option[UUID] = Try(UUID.fromString(s)).toOption }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "habits" / UUIDVar(id) =>
      service.getHabit(id).flatMap {
        case Right(h) => Ok(h)
        case Left(e)  => ErrorHandler.toResponse(e)
      }
    case GET -> Root / "habits" / _ =>
      BadRequest(ErrorResponse("Invalid UUID"))
  }
}
```
- Routes are `HttpRoutes[IO]`, combined with `<+>` (from `cats.syntax.semigroupk._`).
- `orNotFound` converts to `HttpApp[IO]` for the server.
- Decode failures from Circe are caught via `handleErrorWith { case _: DecodeFailure => BadRequest(...) }`.

**JSON:** `import org.http4s.circe.CirceEntityCodec._` (auto encoder/decoder for any Circe-codec type).
Do NOT use `resp.as[String]` in tests — it goes through Circe. Use `resp.bodyText.compile.string` instead.

**Optional query params:**
```scala
private object FromParam extends OptionalQueryParamDecoderMatcher[String]("from")
case GET -> Root / "habits" / UUIDVar(id) / "completions" :? FromParam(fromOpt) +& ToParam(toOpt) =>
```

**DI wiring:** AppResources.scala is a `Resource[IO, AppResources]` for-comprehension.
Main.scala calls `AppResources.make.use(resources => EmberServerBuilder...withHttpApp(resources.routes.orNotFound).build.useForever)`.

**Testing route classes:** Use `AsyncWordSpec with AsyncIOSpec with Matchers` (cats-effect-testing-scalatest).
```scala
private def appWith(service: HabitService): HttpApp[IO] = new HabitRoutes(service).routes.orNotFound
// In test body:
appWith(service).run(req).flatMap { resp =>
  resp.bodyText.compile.string.map { body => decode[HabitResponse](body).isRight shouldBe true }
}
```

**No ActorSystem, no materializer, no ExecutionContext except in DatabaseConfig.**
