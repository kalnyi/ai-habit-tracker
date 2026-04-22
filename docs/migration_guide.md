# STACK MIGRATION TASK
## Akka HTTP → http4s + Cats Effect

### EXECUTION INSTRUCTIONS
- Execute all steps in order without asking for confirmation
- After each step run `sbt compile` to catch errors early
- Run `sbt test` after Step 6
- If compile or test fails, fix before proceeding to next step
- At the end: update MEMORY files and write ADR entry
- Report a single summary when fully complete

---

## CONTEXT

The existing CRUD uses Akka HTTP with Doobie. These have incompatible effect
models: Akka HTTP runs on Future, Doobie speaks Cats IO. Every route handler
contains a Future/IO bridge that caused excessive token usage and wiring bugs
during agent development. The goal is a single coherent effect model end to end:
http4s + Cats Effect + Doobie all speak IO natively, no bridge layer needed.

### What changes
- HTTP layer: Akka HTTP → http4s Ember
- JSON: spray-json → Circe via http4s-circe
- Entry point: App + ActorSystem → IOApp.Simple
- DI wiring: implicit ActorSystem passing → AppResources Resource
- Tests: ScalatestRouteTest → munit-cats-effect

### What does NOT change
- Doobie queries and transactors (already Cats IO)
- Case classes and domain model
- Postgres schema and Docker Compose
- Business logic in service layer
- Circe codecs if already present

---

## STEP 1 — UPDATE build.sbt

### Remove these dependencies entirely
```
"com.typesafe.akka" %% "akka-http"
"com.typesafe.akka" %% "akka-http-spray-json"
"com.typesafe.akka" %% "akka-stream"
"com.typesafe.akka" %% "akka-actor-typed"
"com.typesafe.akka" %% "akka-actor"
"io.spray"          %% "spray-json"
"com.typesafe.akka" %% "akka-http-testkit"
```

### Add these dependencies
```scala
val http4sVersion = "0.23.27"

"org.http4s"    %% "http4s-ember-server" % http4sVersion,
"org.http4s"    %% "http4s-ember-client" % http4sVersion,
"org.http4s"    %% "http4s-circe"        % http4sVersion,
"org.http4s"    %% "http4s-dsl"          % http4sVersion,
"org.typelevel" %% "cats-effect"         % "3.5.4",

// sttp for Anthropic API calls in later phases
"com.softwaremill.sttp.client3" %% "cats"  % "3.9.7",
"com.softwaremill.sttp.client3" %% "circe" % "3.9.7",

// Tapir for type-safe endpoints + auto Swagger
val tapirVersion = "1.10.7"
"com.softwaremill.sttp.tapir" %% "tapir-http4s-server"     % tapirVersion,
"com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirVersion,
"com.softwaremill.sttp.tapir" %% "tapir-circe"             % tapirVersion,

// Test
"org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test,
"org.http4s"    %% "http4s-laws"       % http4sVersion % Test,
```

### Keep unchanged
```
"org.tpolecat" %% "doobie-core"
"org.tpolecat" %% "doobie-postgres"
"org.tpolecat" %% "doobie-hikari"
"io.circe"     %% "circe-generic"
"io.circe"     %% "circe-parser"
"io.circe"     %% "circe-core"
```

### Version constraints
- http4s 0.23.x is the Cats Effect 3 line — do not use 0.21.x
- Doobie must be 1.0.0-RC4 or later for CE3 compatibility
- If Doobie version is older than RC4, upgrade it now

---

## STEP 2 — REPLACE Main.scala

Delete all existing content. Replace with:

```scala
import cats.effect.{IO, IOApp}
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder

object Main extends IOApp.Simple {

  def run: IO[Unit] =
    AppResources.make[IO].use { resources =>
      EmberServerBuilder
        .default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(port"8080")
        .withHttpApp(resources.routes.orNotFound)
        .build
        .useForever
    }
}
```

No ActorSystem. No ExecutionContext. No implicit materializer.
IOApp.Simple provides the runtime.

---

## STEP 3 — CREATE AppResources.scala

Create this file in the same package as Main:

```scala
import cats.effect.{Async, Resource}
import org.http4s.HttpRoutes

final case class AppResources[F[_]](routes: HttpRoutes[F])

object AppResources {

  def make[F[_]: Async]: Resource[F, AppResources[F]] =
    for {
      xa      <- DatabaseConfig.transactor[F]
      repo    =  new HabitRepository(xa)
      service =  new HabitService(repo)
      routes  =  new HabitRoutes[F](service).routes
    } yield AppResources(routes)
}
```

If there are additional repositories or services in the existing codebase,
add them to this for-comprehension following the same pattern.
This is the only dependency injection wiring file — consolidate all wiring here.

---

## STEP 4 — UPDATE DatabaseConfig.scala

Replace the existing transactor construction with a Resource-based version:

```scala
import cats.effect.{Async, Resource}
import doobie.hikari.HikariTransactor
import scala.concurrent.ExecutionContext

object DatabaseConfig {

  def transactor[F[_]: Async]: Resource[F, HikariTransactor[F]] = {
    val connectEC = ExecutionContext.fromExecutor(
      java.util.concurrent.Executors.newFixedThreadPool(8)
    )
    HikariTransactor.newHikariTransactor[F](
      "org.postgresql.Driver",
      sys.env.getOrElse("DB_URL",      "jdbc:postgresql://localhost:5432/habits"),
      sys.env.getOrElse("DB_USER",     "postgres"),
      sys.env.getOrElse("DB_PASSWORD", "postgres"),
      connectEC
    )
  }
}
```

Remove any .unsafeRunSync() calls on the transactor.
Remove any implicit ExecutionContext parameters sourced from ActorSystem.

---

## STEP 5 — REWRITE ROUTE HANDLERS

For each existing route file, apply these translation rules:

### Imports — remove all akka imports, add:
```scala
import cats.effect.Async
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.circe.CirceEntityCodec._
import io.circe.generic.auto._
```

### Class signature
```scala
// BEFORE
class HabitRoutes(service: HabitService)(implicit system: ActorSystem)

// AFTER
class HabitRoutes[F[_]: Async](service: HabitService[F]) extends Http4sDsl[F]
```

### Route wrapper
```scala
// BEFORE
val routes: Route = ...

// AFTER
val routes: HttpRoutes[F] = HttpRoutes.of[F] {
  ...
}
```

### Route pattern translation
```
// BEFORE                                    // AFTER
path("habits") { get { ... } }          →   case GET -> Root / "habits" =>
path("habits" / LongNumber) { id =>     →   case GET -> Root / "habits" / LongVar(id) =>
complete(value)                         →   Ok(value)
complete(StatusCodes.Created, value)    →   Created(value)
complete(StatusCodes.NoContent)         →   NoContent()
complete(StatusCodes.NotFound)          →   NotFound()
entity(as[T]) { body => ... }           →   req.as[T].flatMap { body => ... }
extractRequest { req => ... }           →   req @ METHOD -> pattern
onSuccess(future) { result => ... }     →   io.flatMap { result => ... }
```

### Full route example for reference
```scala
val routes: HttpRoutes[F] = HttpRoutes.of[F] {

  case GET -> Root / "habits" =>
    service.getAll.flatMap(Ok(_))

  case GET -> Root / "habits" / LongVar(id) =>
    service.findById(id).flatMap {
      case Some(h) => Ok(h)
      case None    => NotFound()
    }

  case req @ POST -> Root / "habits" =>
    req.as[CreateHabitRequest].flatMap { body =>
      service.create(body).flatMap(Created(_))
    }

  case req @ PUT -> Root / "habits" / LongVar(id) =>
    req.as[UpdateHabitRequest].flatMap { body =>
      service.update(id, body).flatMap {
        case Some(h) => Ok(h)
        case None    => NotFound()
      }
    }

  case DELETE -> Root / "habits" / LongVar(id) =>
    service.delete(id) >> NoContent()

  case req @ POST -> Root / "habits" / LongVar(id) / "completions" =>
    req.as[LogCompletionRequest].flatMap { body =>
      service.logCompletion(id, body).flatMap(Created(_))
    }

  case GET -> Root / "habits" / LongVar(id) / "completions" =>
    service.getCompletions(id).flatMap(Ok(_))
}
```

Apply this pattern to ALL existing route files in the project.
Do not leave any Akka HTTP Route definitions in place.

---

## STEP 6 — UPDATE TESTS

### Dependencies (already added in Step 1)
Remove akka-http-testkit. Use munit-cats-effect.

### Test class translation
```scala
// BEFORE
class HabitRoutesSpec extends AnyWordSpec
  with Matchers with ScalatestRouteTest {

  val routes = new HabitRoutes(mockService).routes

  "GET /habits" should {
    "return 200" in {
      Get("/habits") ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
    }
  }
}

// AFTER
import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.implicits._

class HabitRoutesSpec extends CatsEffectSuite {

  val routes = new HabitRoutes[IO](new MockHabitService).routes.orNotFound

  test("GET /habits returns 200") {
    val req = Request[IO](Method.GET, uri"/habits")
    routes.run(req).map { resp =>
      assertEquals(resp.status, Status.Ok)
    }
  }

  test("POST /habits returns 201") {
    import org.http4s.circe.CirceEntityCodec._
    val body = CreateHabitRequest("Morning run", "daily")
    val req  = Request[IO](Method.POST, uri"/habits").withEntity(body)
    routes.run(req).flatMap { resp =>
      assertEquals(resp.status, Status.Created)
      resp.as[Habit].map(h => assertEquals(h.name, "Morning run"))
    }
  }
}
```

Translate ALL existing route tests following this pattern.
Service and repository unit tests that do not involve HTTP need no changes.

---

## STEP 7 — REMOVE RESIDUAL AKKA REFERENCES

After completing Steps 1–6, scan the entire codebase:

```bash
grep -r "akka" src/ --include="*.scala" -l
grep -r "ActorSystem" src/ --include="*.scala" -l
grep -r "ExecutionContext" src/ --include="*.scala" -l
grep -r "Future" src/ --include="*.scala" -l
grep -r "spray" src/ --include="*.scala" -l
```

For each file found:
- Remove akka imports
- Replace Future[T] return types with F[T] or IO[T]
- Remove ActorSystem and materializer parameters
- Replace ExecutionContext implicits with Async[F] typeclass

Do not remove ExecutionContext inside DatabaseConfig — that usage is correct.

---

## STEP 8 — COMPILE AND TEST

```bash
sbt clean compile
sbt test
```

Fix all errors before proceeding to Step 9.

Common errors and fixes:
- "could not find implicit value for parameter cs: ContextShift" → wrong CE version, check deps
- "value ~> is not a member" → Akka HTTP import still present somewhere
- "could not find implicit value for Async" → missing [F[_]: Async] type parameter
- "diverging implicit expansion" → likely mixing CE2 and CE3 deps, check versions
- "object dsl is not a member of package http4s" → missing Http4sDsl extension

---

## STEP 9 — UPDATE MEMORY FILES AND WRITE ADR

After all tests pass, update agent MEMORY files and write an ADR.

### For each MEMORY file (architect, developer, reviewer):

Read the current MEMORY file, then update these sections:

**Architect MEMORY — update:**
- Stack decisions section: record http4s replaces Akka HTTP, reason is CE3 coherence
- Dependency versions: record exact versions added in Step 1
- Architectural patterns: record AppResources as the DI pattern for this project
- Effect model: record that all async code uses F[_]: Async / IO, no Future

**Developer MEMORY — update:**
- HTTP framework: http4s with Ember server
- Route pattern: HttpRoutes.of[F] with pattern matching DSL
- JSON: Circe via http4s-circe, io.circe.generic.auto._
- Testing: munit-cats-effect, CatsEffectSuite
- DI: AppResources.make[F] Resource for-comprehension
- No ActorSystem, no materializer, no ExecutionContext except in DatabaseConfig

**Reviewer MEMORY — update:**
- Correct route return type: HttpRoutes[F], not Route
- Correct test base class: CatsEffectSuite
- No Future in route handlers or services is a review criterion
- No akka imports anywhere except build.sbt history is a review criterion

### ADR entry — write to docs/ADRs.md (create if not exists):

```
## ADR-XXX — Migrate HTTP layer from Akka HTTP to http4s
Date: [today's date]
Status: Accepted
Author: Human engineer (manual migration)

### Decision
Replace Akka HTTP with http4s (Ember server) as the HTTP layer.

### Context
The existing stack combined Akka HTTP (Future-based) with Doobie
(Cats IO-based). Every route handler required a Future/IO bridge via
implicit ExecutionContext, causing high token usage during agent-driven
development and subtle wiring bugs. The agent swarm had difficulty
reasoning about two concurrent effect models in the same codebase.

### Consequences
- All async code now uses a single effect model: Cats Effect IO / F[_]: Async
- Route handlers return HttpRoutes[F], eliminating all bridge code
- sttp with cats-effect backend integrates cleanly for Anthropic API calls
- AppResources.scala is the single DI wiring point for all components
- Akka dependencies removed entirely — no ActorSystem, no materializer

### Alternatives considered
- Keep Akka HTTP, wrap Doobie calls in Future: rejected, increases complexity
- Switch to ZIO HTTP: rejected, team is learning Cats Effect ecosystem
- Play Framework + Slick: rejected, diverges from existing Doobie investment
```

---

## COMPLETION REPORT

When fully done, output a report in this format:

```
MIGRATION COMPLETE
==================
Files modified:   [list]
Files created:    [list]
Files deleted:    [list]
sbt compile:      PASS / FAIL
sbt test:         PASS / FAIL (N passing, N failing)
MEMORY updated:   [list of MEMORY files touched]
ADR written:      docs/ADRs.md entry ADR-XXX
Residual akka:    NONE / [list any remaining if intentional]

Issues encountered:
[any non-trivial problems and how they were resolved]

Ready for Phase 1: YES / NO
If NO: [what needs to be resolved first]
```
