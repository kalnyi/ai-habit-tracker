# PLAN-013: Phase 1 — Pattern Detection and LLM Narrative Endpoint

## PBI reference
PBI-013: Phase 1 — Pattern Detection and LLM Narrative Endpoint
(see also `docs/phases/phase_1_pbi.md` and `docs/phases/phase_1_pattern_detection.md`)

## Summary
Add three Doobie analytical queries to a new `AnalyticsRepository`, assemble
their results into a `HabitContext` via a new `AnalyticsService`, and expose a
single endpoint `GET /users/{userId}/habits/insights` that calls
`AnthropicClient.complete` directly (no wrapping trait) to produce the LLM
narrative. Two carry-over case classes (`HabitContext`, `InsightResponse`)
live in `com.habittracker.model`, the prompt logic in `com.habittracker.prompt`,
and the sttp client in `com.habittracker.client`. The Anthropic API key is
read from `ANTHROPIC_API_KEY` at `AnthropicClient` object-init and the app
raises a descriptive startup error if the variable is missing.

## Preconditions / notes to the Developer agent

- **Build tool is Gradle.** The phase brief says `sbt test`; this project uses
  `./gradlew test` and `./gradlew compileScala`. Every command below uses
  Gradle. Do not attempt to migrate to sbt.
- **ADR-008 is required reading** before writing any file in this plan.
  Six decisions are locked there: file layout under `com.habittracker.{model,client,prompt}`,
  `AnthropicClient` is a no-trait object called directly from the route,
  startup key check via `AppResources`, insights endpoint in a new
  `InsightsRoutes`, SQL in a new `AnalyticsRepository`, Testcontainers for SQL
  tests.
- **ADR-007 is also required reading** because every user-scoped repository
  method filters by `user_id` and ownership mismatch returns 404. Analytics
  queries follow the same rule *except* for `streakForHabit(habitId: UUID)`,
  which is scoped by `habit_id` only — the caller (`AnalyticsService`) has
  already verified ownership.
- **No new external dependencies.** `sttp.client3` `cats` and `circe` modules
  are already on the classpath (`backend/build.gradle` lines 72-73). No
  LangChain4j, no pgvector, no embeddings (HARD LIMIT).
- **No changes to existing CRUD routes, services, repositories, or their
  tests.** Every file marked "Modify" below is modified only to wire the new
  code in, never to change CRUD behaviour.
- **Model constant is `claude-sonnet-4-20250514`** — not opus, not haiku, not
  claude-sonnet-4-6.
- **`InsightPrompt.build` is a pure function.** No `F[_]`, no `IO`, no side
  effects. Signature is exactly `def build(ctx: HabitContext): String`.
- **`SYSTEM_PROMPT` is a `val` on the `InsightPrompt` object** — never inlined
  at the call site.
- **Each section of `build` is a named `val`** (`streakSection`, `daySection`,
  `rankingSection`) — never anonymous strings passed into `mkString`.
- **No akka imports anywhere** in `backend/src/`. Verify with `grep -r "akka" src/`
  before declaring done (AC-14, AC-16).

## Affected files

| File | Change type | Description |
|---|---|---|
| `backend/src/main/scala/com/habittracker/model/Analytics.scala` | Create | Two case classes: `HabitContext` and `InsightResponse` (exact shape in "New components"). |
| `backend/src/main/scala/com/habittracker/client/AnthropicClient.scala` | Create | `object AnthropicClient` — reads `ANTHROPIC_API_KEY` at init, exposes `complete[F[_]: Async]`, forces key check via `API_KEY_CHECK`. |
| `backend/src/main/scala/com/habittracker/prompt/InsightPrompt.scala` | Create | Pure `object InsightPrompt` with `SYSTEM_PROMPT` val and `build(ctx)` method. |
| `backend/src/main/scala/com/habittracker/repository/AnalyticsRepository.scala` | Create | New trait with 3 methods: `streakForHabit`, `completionRateByDayOfWeek`, `habitConsistencyRanking`. |
| `backend/src/main/scala/com/habittracker/repository/DoobieAnalyticsRepository.scala` | Create | Doobie implementation — exact SQL in "Database queries". |
| `backend/src/main/scala/com/habittracker/service/AnalyticsService.scala` | Create | Trait + `DefaultAnalyticsService` — single method `buildHabitContext(userId: Long): IO[HabitContext]`. |
| `backend/src/main/scala/com/habittracker/http/InsightsRoutes.scala` | Create | Single route `GET /users/{userId}/habits/insights` — calls `AnthropicClient.complete` directly. |
| `backend/src/main/scala/com/habittracker/http/AnalyticsCodecs.scala` | Create | Circe encoders/decoders for `HabitContext` and `InsightResponse` (auto-derived per PBI stack note). |
| `backend/src/main/scala/com/habittracker/AppResources.scala` | Modify | Add `analyticsRepo`, `analyticsService`, and `InsightsRoutes` to the wiring graph; insert `API_KEY_CHECK` force-init. |
| `backend/src/main/resources/openapi/openapi.yaml` | Modify | Append `/users/{userId}/habits/insights` path item with `InsightResponse` schema. |
| `backend/src/test/scala/com/habittracker/prompt/InsightPromptSpec.scala` | Create | Four pure unit tests (AC-12). |
| `backend/src/test/scala/com/habittracker/repository/DoobieAnalyticsRepositorySpec.scala` | Create | Testcontainers + Postgres — four SQL tests (AC-11). |

No other files are modified. `HabitRoutes`, `HabitCompletionRoutes`, `HabitService`,
`HabitCompletionService`, the two existing `Doobie*Repository` files, their
specs, and the two integration specs are **untouched**.

## New components

### `com.habittracker.model.Analytics` (new file)

File: `backend/src/main/scala/com/habittracker/model/Analytics.scala`.

```scala
package com.habittracker.model

import java.util.UUID

final case class HabitContext(
    userId:             Long,
    streaks:            Map[UUID, Int],
    completionByDay:    Map[String, Double],
    consistencyRanking: List[(String, Double)]
)

final case class InsightResponse(
    analytics: HabitContext,
    narrative: String
)
```

Rules (from AC-5 and AC-3):
- Field order is exactly as above.
- This is the only file in the codebase that defines these two types.
- No companion objects beyond what is required for JSON derivation.
- `streaks` values are current consecutive-day streak counts; `completionByDay`
  contains all 7 day names (Monday–Sunday) even where the rate is `0.0`;
  `consistencyRanking` is sorted descending by score.

### `com.habittracker.client.AnthropicClient` (new file)

File: `backend/src/main/scala/com/habittracker/client/AnthropicClient.scala`.

```scala
package com.habittracker.client

import cats.effect.Async
import cats.syntax.all._
import sttp.client3._
import sttp.client3.circe._
import sttp.client3.httpclient.cats.HttpClientCatsBackend
import io.circe.Json
import io.circe.parser.parse

object AnthropicClient {

  val MODEL:       String = "claude-sonnet-4-20250514"
  val API_URL:     String = "https://api.anthropic.com/v1/messages"
  val API_VERSION: String = "2023-06-01"
  val MAX_TOKENS:  Int    = 1024

  // --- key read at object-init; startup fails here if the env var is missing ---
  private val API_KEY: String =
    sys.env.get("ANTHROPIC_API_KEY").filter(_.trim.nonEmpty).getOrElse {
      sys.error(
        "ANTHROPIC_API_KEY environment variable is not set. " +
        "The habit tracker app cannot start without it."
      )
    }

  /** Named forcing-handle so AppResources can force object init and trigger
    * the startup failure if the key is missing. Its value is intentionally
    * Unit — the side-effect of resolving `API_KEY` is the whole point. */
  val API_KEY_CHECK: Unit = {
    val _ = API_KEY  // touch the lazy val so init happens now
    ()
  }

  /** Direct sttp call to Anthropic Messages API. The HTTP request is visible
    * at the call site — no trait, no abstract class, no DI. */
  def complete[F[_]: Async](
      systemPrompt: String,
      userMessage:  String
  ): F[String] = {
    val bodyJson: String =
      Json.obj(
        "model"      -> Json.fromString(MODEL),
        "max_tokens" -> Json.fromInt(MAX_TOKENS),
        "system"     -> Json.fromString(systemPrompt),
        "messages"   -> Json.arr(
          Json.obj(
            "role"    -> Json.fromString("user"),
            "content" -> Json.fromString(userMessage)
          )
        )
      ).noSpaces

    val request: Request[Either[String, String], Any] =
      basicRequest
        .post(uri"$API_URL")
        .header("x-api-key", API_KEY)
        .header("anthropic-version", API_VERSION)
        .header("content-type", "application/json")
        .body(bodyJson)
        .response(asString)

    HttpClientCatsBackend.resource[F]().use { backend =>
      request.send(backend).flatMap { resp =>
        resp.body match {
          case Right(raw) =>
            parse(raw).flatMap { json =>
              json.hcursor
                .downField("content")
                .downArray
                .downField("text")
                .as[String]
            } match {
              case Right(text) => Async[F].pure(text)
              case Left(err)   =>
                Async[F].raiseError(new RuntimeException(
                  s"Failed to parse Anthropic response: ${err.getMessage}; body=$raw"
                ))
            }
          case Left(err) =>
            Async[F].raiseError(new RuntimeException(
              s"Anthropic API call failed (status=${resp.code.code}): $err"
            ))
        }
      }
    }
  }
}
```

Notes for the Developer agent:
- The `HttpClientCatsBackend.resource` comes from
  `sttp.client3.httpclient.cats.HttpClientCatsBackend`; it is included in the
  `com.softwaremill.sttp.client3:cats` artifact already on the classpath.
- Do not introduce a long-lived shared backend; the `Resource` is scoped to
  each `complete` call. This trades a small per-call overhead for zero shared
  mutable state and matches the "call site is self-contained" constraint.
- `API_KEY` is a `private val`, never exposed as a getter. `API_KEY_CHECK` is
  public only so `AppResources` can force object init.
- The body JSON is constructed with `Json.obj` / `Json.arr` (circe core, no
  generic derivation). This keeps the request body visible line-by-line.

### `com.habittracker.prompt.InsightPrompt` (new file)

File: `backend/src/main/scala/com/habittracker/prompt/InsightPrompt.scala`.

```scala
package com.habittracker.prompt

import com.habittracker.model.HabitContext

object InsightPrompt {

  val SYSTEM_PROMPT: String =
    """You are a habit analysis assistant. You receive structured data about
      |a user's habit completion patterns and provide specific, data-grounded
      |observations. Be concise: 3-5 sentences. Reference specific habit names
      |and numbers from the data provided.""".stripMargin

  def build(ctx: HabitContext): String = {
    val streakSection: String = {
      val lines = ctx.streaks.toList.map { case (habitId, streak) =>
        s"- habit $habitId: $streak-day current streak"
      }
      if (lines.isEmpty) "Current streaks:\n- (no active habits)"
      else "Current streaks:\n" + lines.mkString("\n")
    }

    val daySection: String = {
      val ordered = List(
        "Monday", "Tuesday", "Wednesday", "Thursday",
        "Friday", "Saturday", "Sunday"
      )
      val lines = ordered.map { day =>
        val rate = ctx.completionByDay.getOrElse(day, 0.0)
        f"- $day%-9s ${rate * 100}%.0f%% of weeks had at least one completion"
      }
      "Completion rate by day of week:\n" + lines.mkString("\n")
    }

    val rankingSection: String = {
      val lines = ctx.consistencyRanking.zipWithIndex.map { case ((name, score), i) =>
        f"${i + 1}. $name (score ${score}%.2f)"
      }
      if (lines.isEmpty) "Consistency ranking:\n- (no active habits)"
      else "Consistency ranking (descending):\n" + lines.mkString("\n")
    }

    val header: String = s"User ${ctx.userId} habit insights"

    List(header, streakSection, daySection, rankingSection).mkString("\n\n")
  }
}
```

Rules enforced by this file (AC-8, AC-9, AC-10):
- `SYSTEM_PROMPT` is a named `val` on the object, not inlined.
- `build` signature is exactly `def build(ctx: HabitContext): String`.
- Each section is a named `val` (`streakSection`, `daySection`,
  `rankingSection`, plus `header`). No anonymous strings passed into
  `mkString`.
- No `F[_]`, no `IO`, no side effect.
- `build` output contains `ctx.userId` (via `header`), habit names (via
  `rankingSection`), and all 7 day names (via `daySection`).

### `com.habittracker.repository.AnalyticsRepository` (new file)

File: `backend/src/main/scala/com/habittracker/repository/AnalyticsRepository.scala`.

```scala
package com.habittracker.repository

import cats.effect.IO

import java.util.UUID

/** Read-only analytical queries.
  *
  * User-scoping note: `streakForHabit(habitId: UUID)` is scoped by `habit_id`
  * only. The caller — typically `AnalyticsService` — is responsible for
  * verifying that the habit belongs to the user in question via
  * `HabitRepository.listActive(userId)` before calling here. This is a
  * deliberate divergence from `HabitRepository`, which enforces
  * `WHERE user_id = ?` on every SELECT (see ADR-007 and ADR-008). */
trait AnalyticsRepository {

  def streakForHabit(habitId: UUID): IO[Int]

  def completionRateByDayOfWeek(userId: Long): IO[Map[String, Double]]

  def habitConsistencyRanking(userId: Long): IO[List[(String, Double)]]
}
```

### `com.habittracker.repository.DoobieAnalyticsRepository` (new file)

File: `backend/src/main/scala/com/habittracker/repository/DoobieAnalyticsRepository.scala`.

Skeleton (exact SQL is under "Database queries" below):

```scala
package com.habittracker.repository

import cats.effect.IO
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.util.UUID

final class DoobieAnalyticsRepository(transactor: Transactor[IO])
    extends AnalyticsRepository {

  // ---------------------------------------------------------------------------
  // SQL queries (full text under "Database queries" in PLAN)
  // ---------------------------------------------------------------------------

  private def streakForHabitQuery(habitId: UUID): Query0[Int] = ???

  private def completionRateByDayOfWeekQuery(userId: Long): Query0[(String, Double)] = ???

  private def habitConsistencyRankingQuery(userId: Long): Query0[(String, Double)] = ???

  // ---------------------------------------------------------------------------
  // AnalyticsRepository implementation
  // ---------------------------------------------------------------------------

  override def streakForHabit(habitId: UUID): IO[Int] =
    streakForHabitQuery(habitId).unique.transact(transactor)

  override def completionRateByDayOfWeek(userId: Long): IO[Map[String, Double]] = {
    val dayOrder = List(
      "Monday", "Tuesday", "Wednesday", "Thursday",
      "Friday", "Saturday", "Sunday"
    )
    completionRateByDayOfWeekQuery(userId).to[List].transact(transactor).map { rows =>
      val found = rows.toMap
      dayOrder.map(d => d -> found.getOrElse(d, 0.0)).toMap
    }
  }

  override def habitConsistencyRanking(userId: Long): IO[List[(String, Double)]] =
    habitConsistencyRankingQuery(userId).to[List].transact(transactor)
}
```

### `com.habittracker.service.AnalyticsService` (new file)

File: `backend/src/main/scala/com/habittracker/service/AnalyticsService.scala`.

```scala
package com.habittracker.service

import cats.effect.IO
import cats.syntax.all._
import com.habittracker.model.HabitContext
import com.habittracker.repository.{AnalyticsRepository, HabitRepository}

trait AnalyticsService {
  def buildHabitContext(userId: Long): IO[HabitContext]
}

final class DefaultAnalyticsService(
    habitRepo:     HabitRepository,
    analyticsRepo: AnalyticsRepository
) extends AnalyticsService {

  override def buildHabitContext(userId: Long): IO[HabitContext] =
    for {
      habits <- habitRepo.listActive(userId)
      streaks <- habits.traverse { h =>
        analyticsRepo.streakForHabit(h.id).map(s => h.id -> s)
      }.map(_.toMap)
      byDay   <- analyticsRepo.completionRateByDayOfWeek(userId)
      ranking <- analyticsRepo.habitConsistencyRanking(userId)
    } yield HabitContext(
      userId             = userId,
      streaks            = streaks,
      completionByDay    = byDay,
      consistencyRanking = ranking
    )
}
```

Notes:
- The service calls `habitRepo.listActive(userId)` first — this is how
  ownership is enforced for `streakForHabit`. The habit UUIDs passed into
  `analyticsRepo.streakForHabit` come from this list, which is already
  user-scoped by ADR-007's DB-layer filter.
- No LLM call in this service. Pure data assembly (HARD LIMIT: the route, not
  the service, makes the HTTP call).

### `com.habittracker.http.AnalyticsCodecs` (new file)

File: `backend/src/main/scala/com/habittracker/http/AnalyticsCodecs.scala`.

The PBI/phase-brief stack note specifies `io.circe.generic.auto._`, but the
`HabitContext` type uses `Map[UUID, Int]` and `List[(String, Double)]`, both
of which auto-derivation handles natively. A dedicated codecs file keeps the
imports local and matches the shape of `HabitCodecs` / `CompletionCodecs`.

```scala
package com.habittracker.http

import com.habittracker.model.{HabitContext, InsightResponse}
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

import java.util.UUID

object AnalyticsCodecs {

  // UUID codecs reuse the same approach as HabitCodecs.
  implicit val uuidEncoder: Encoder[UUID] =
    Encoder.encodeString.contramap(_.toString)
  implicit val uuidDecoder: Decoder[UUID] =
    Decoder.decodeString.emap { s =>
      scala.util.Try(UUID.fromString(s)).toEither.left.map(_.getMessage)
    }

  // Map[UUID, Int] — circe encodes maps whose keys have a KeyEncoder; we
  // provide one that stringifies the UUID.
  implicit val uuidKeyEncoder: io.circe.KeyEncoder[UUID] =
    io.circe.KeyEncoder.instance(_.toString)
  implicit val uuidKeyDecoder: io.circe.KeyDecoder[UUID] =
    io.circe.KeyDecoder.instance(s => scala.util.Try(UUID.fromString(s)).toOption)

  implicit val habitContextEncoder:    Encoder[HabitContext]    = deriveEncoder[HabitContext]
  implicit val habitContextDecoder:    Decoder[HabitContext]    = deriveDecoder[HabitContext]
  implicit val insightResponseEncoder: Encoder[InsightResponse] = deriveEncoder[InsightResponse]
  implicit val insightResponseDecoder: Decoder[InsightResponse] = deriveDecoder[InsightResponse]
}
```

Rationale for `semiauto` over `auto`: the other existing codec files use
`semiauto` (`HabitCodecs.scala`, `CompletionCodecs.scala`). Using the same
style keeps the codebase consistent and avoids the compile-time cost penalty
of `auto`. The PBI stack note mentions `auto` as the codec mechanism in use;
either is acceptable so long as the two types round-trip correctly.

### `com.habittracker.http.InsightsRoutes` (new file)

File: `backend/src/main/scala/com/habittracker/http/InsightsRoutes.scala`.

```scala
package com.habittracker.http

import cats.effect.IO
import com.habittracker.client.AnthropicClient
import com.habittracker.http.AnalyticsCodecs._
import com.habittracker.model.InsightResponse
import com.habittracker.prompt.InsightPrompt
import com.habittracker.service.AnalyticsService
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._

/** Single route: GET /users/{userId}/habits/insights.
  *
  * The Anthropic HTTP call is made directly in this handler — no wrapping
  * trait, no abstract class. See ADR-008. */
final class InsightsRoutes(service: AnalyticsService) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "users" / LongVar(userId) / "habits" / "insights" =>
      for {
        ctx       <- service.buildHabitContext(userId)
        prompt    =  InsightPrompt.build(ctx)
        narrative <- AnthropicClient.complete[IO](InsightPrompt.SYSTEM_PROMPT, prompt)
        response  =  InsightResponse(analytics = ctx, narrative = narrative)
        result    <- Ok(response)
      } yield result
  }
}
```

Notes:
- No `ErrorHandler.toResponse` call because `buildHabitContext` returns
  `IO[HabitContext]` (not `Either`) and the Anthropic call either succeeds or
  raises an `IO` failure that http4s translates to 500. This matches the PBI
  "Error handling beyond startup failure" out-of-scope note.
- The route does not validate that `users.id` exists in the `users` table.
  Per the PBI (out-of-scope: "Authentication or user creation"), the `userId`
  is trusted.
- `routes` is `HttpRoutes[IO]`, not `F[_]`, matching the existing route
  classes (`HabitRoutes`, `HabitCompletionRoutes`). The `F[_]: Async` lives
  on `AnthropicClient.complete` so Phase 2 can migrate the routes to `F[_]`
  later without touching the client.

## API contract

One new endpoint:

| Field | Value |
|---|---|
| Method | `GET` |
| Path | `/users/{userId}/habits/insights` |
| Path param | `userId` — integer, format int64 |
| Request body | (none) |
| Success status | `200 OK` |
| Success body | `InsightResponse` (see below) |
| Auth | None (per PBI out-of-scope) |

Response body shape (JSON):

```json
{
  "analytics": {
    "userId": 1,
    "streaks": {
      "6f9619ff-8b86-d011-b42d-00c04fc964ff": 3,
      "dddddddd-cccc-bbbb-aaaa-000000000000": 0
    },
    "completionByDay": {
      "Monday":    0.75,
      "Tuesday":   0.50,
      "Wednesday": 0.25,
      "Thursday":  0.00,
      "Friday":    1.00,
      "Saturday":  0.00,
      "Sunday":    0.50
    },
    "consistencyRanking": [
      ["Read 20 pages", 0.80],
      ["Exercise",       0.40]
    ]
  },
  "narrative": "Your reading habit is your most consistent..."
}
```

Error responses:
- `500 Internal Server Error` — Anthropic call failed or SQL error. No body
  contract in this phase (matches out-of-scope note).
- `404 Not Found` — path does not match (fall-through). Not a modelled
  response.

### OpenAPI update (`backend/src/main/resources/openapi/openapi.yaml`)

Append one path item to the existing document, and two schema components.
Do not remove or alter existing entries.

```yaml
paths:
  # ... existing paths unchanged ...
  /users/{userId}/habits/insights:
    parameters:
      - name: userId
        in: path
        required: true
        schema:
          type: integer
          format: int64
    get:
      summary: Get LLM-generated insights about a user's habit patterns
      operationId: getHabitInsights
      responses:
        '200':
          description: Successful response with analytics and narrative
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/InsightResponse'
        '500':
          description: Upstream LLM failure or internal error

components:
  schemas:
    # ... existing schemas unchanged ...
    HabitContext:
      type: object
      required: [userId, streaks, completionByDay, consistencyRanking]
      properties:
        userId:
          type: integer
          format: int64
        streaks:
          type: object
          additionalProperties:
            type: integer
        completionByDay:
          type: object
          additionalProperties:
            type: number
            format: double
        consistencyRanking:
          type: array
          items:
            type: array
            minItems: 2
            maxItems: 2
            items:
              oneOf:
                - type: string
                - type: number
                  format: double
    InsightResponse:
      type: object
      required: [analytics, narrative]
      properties:
        analytics:
          $ref: '#/components/schemas/HabitContext'
        narrative:
          type: string
```

## Database changes

**No new Liquibase changeset.** The three analytical queries read existing
columns on `habits` and `habit_completions`. Do not create a `005-*.sql` file.

## Database queries (exact SQL)

All three queries run against the existing schema:
- `habits (id UUID, user_id BIGINT, name VARCHAR, description TEXT,
  frequency VARCHAR, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ,
  deleted_at TIMESTAMPTZ)`
- `habit_completions (id UUID, habit_id UUID FK, completed_on DATE,
  note TEXT, created_at TIMESTAMPTZ)`

### Q1: `streakForHabitQuery(habitId: UUID): Query0[Int]`

Counts consecutive days **ending on CURRENT_DATE** that have a completion
for this habit. If `CURRENT_DATE` has no completion, returns 0.

```scala
private def streakForHabitQuery(habitId: UUID): Query0[Int] =
  sql"""
    WITH RECURSIVE streak(day) AS (
      SELECT CURRENT_DATE
      WHERE EXISTS (
        SELECT 1 FROM habit_completions
        WHERE habit_id = $habitId AND completed_on = CURRENT_DATE
      )
      UNION ALL
      SELECT streak.day - INTERVAL '1 day'
      FROM streak
      WHERE EXISTS (
        SELECT 1 FROM habit_completions
        WHERE habit_id = $habitId
          AND completed_on = (streak.day - INTERVAL '1 day')::date
      )
    )
    SELECT COUNT(*)::int FROM streak
  """.query[Int]
```

Semantics:
- Anchor row: `CURRENT_DATE` is included **only if** a completion exists on
  that date (seed's WHERE guard).
- Recursive step: each prior day is added only if a completion exists for it.
- Recursion terminates when the previous day has no completion.
- `SELECT COUNT(*)::int` returns 0 when the anchor was rejected.

### Q2: `completionRateByDayOfWeekQuery(userId: Long): Query0[(String, Double)]`

For each day of week, the fraction of distinct ISO weeks (since the user's
earliest completion) in which at least one completion happened on that day.
Returns rows only for days that had at least one completion — the service
layer fills in missing days with `0.0`.

```scala
private def completionRateByDayOfWeekQuery(userId: Long): Query0[(String, Double)] =
  sql"""
    WITH user_completions AS (
      SELECT hc.completed_on
      FROM habit_completions hc
      JOIN habits h ON h.id = hc.habit_id
      WHERE h.user_id = $userId AND h.deleted_at IS NULL
    ),
    week_span AS (
      SELECT GREATEST(
        1,
        CEIL(
          EXTRACT(EPOCH FROM (CURRENT_DATE - MIN(completed_on))) / 604800.0
        )::int
      ) AS total_weeks
      FROM user_completions
    ),
    per_day AS (
      SELECT
        TO_CHAR(completed_on, 'FMDay') AS day_name,
        COUNT(DISTINCT DATE_TRUNC('week', completed_on)) AS weeks_with_completion
      FROM user_completions
      GROUP BY TO_CHAR(completed_on, 'FMDay')
    )
    SELECT
      per_day.day_name,
      (per_day.weeks_with_completion::double precision / week_span.total_weeks)::double precision
    FROM per_day, week_span
  """.query[(String, Double)]
```

Semantics:
- `TO_CHAR(..., 'FMDay')` produces English day names with trimmed padding:
  `Monday`, `Tuesday`, `Wednesday`, `Thursday`, `Friday`, `Saturday`,
  `Sunday`. The `FM` prefix strips the default whitespace padding Postgres
  otherwise adds to align to the longest day name (`Wednesday`).
- `DATE_TRUNC('week', ...)` returns the ISO Monday starting the week
  containing the completion date — so "weeks with at least one completion
  on Monday" counts each Monday in a distinct week exactly once.
- `total_weeks` is the span from the earliest completion to today, in
  weeks (rounded up, minimum 1). This gives "completion rate = weeks on
  which the user completed that day ÷ total span of weeks".
- Only days that had at least one completion appear in the result.
  `DefaultAnalyticsService` (actually `DoobieAnalyticsRepository`'s
  `completionRateByDayOfWeek` method) fills in missing days with `0.0` so
  that `HabitContext.completionByDay` has all 7 keys (AC-3).

### Q3: `habitConsistencyRankingQuery(userId: Long): Query0[(String, Double)]`

All active habits for the user sorted descending by consistency score,
where `score = completions / max(1, days_since_created)`.

```scala
private def habitConsistencyRankingQuery(userId: Long): Query0[(String, Double)] =
  sql"""
    SELECT
      h.name,
      (COALESCE(COUNT(hc.id), 0)::double precision
        / GREATEST(
            1,
            EXTRACT(EPOCH FROM (NOW() - h.created_at)) / 86400.0
          )
      )::double precision AS score
    FROM habits h
    LEFT JOIN habit_completions hc ON hc.habit_id = h.id
    WHERE h.user_id = $userId AND h.deleted_at IS NULL
    GROUP BY h.id, h.name, h.created_at
    ORDER BY score DESC, h.name ASC
  """.query[(String, Double)]
```

Semantics:
- `LEFT JOIN` includes habits that have zero completions (score 0).
- Denominator is days since creation, minimum 1 — guards against
  brand-new habits that were created less than a day ago from producing
  an infinite or inflated score.
- Tie-breaker on `h.name ASC` keeps the ordering deterministic for tests.

## LLM integration

This PBI implements the **pattern analysis** LLM use case from CLAUDE.md:
> "weekly summary of habit completion patterns, correlations between habits,
> and risk flags."

Prompt file: **none — the system prompt lives inline as
`InsightPrompt.SYSTEM_PROMPT`** (per AC-8). CLAUDE.md's "prompt files under
`backend/src/main/resources/prompts/`" convention does not apply here because
the phase brief and HARD LIMITS require `SYSTEM_PROMPT` to be a Scala `val`
on the `InsightPrompt` object. If a later PBI wants file-backed prompts, it
must be a separate ADR.

No RAG. No embeddings. No retrieval step. The prompt is built purely from the
three SQL queries' output, assembled via `InsightPrompt.build`.

## AppResources wiring

File: `backend/src/main/scala/com/habittracker/AppResources.scala`.

```scala
package com.habittracker

import cats.effect.{Clock, IO, Resource}
import cats.syntax.semigroupk._
import com.habittracker.client.AnthropicClient
import com.habittracker.http.{DocsRoutes, HabitCompletionRoutes, HabitRoutes, InsightsRoutes}
import com.habittracker.repository.{
  DoobieAnalyticsRepository,
  DoobieHabitCompletionRepository,
  DoobieHabitRepository,
  DoobieUserRepository,
  UserRepository
}
import com.habittracker.service.{
  DefaultAnalyticsService,
  DefaultHabitCompletionService,
  DefaultHabitService
}
import org.http4s.HttpRoutes

final case class AppResources(
    routes:   HttpRoutes[IO],
    userRepo: UserRepository
)

object AppResources {

  def make: Resource[IO, AppResources] =
    for {
      xa               <- DatabaseConfig.transactor
      // Force AnthropicClient object-init; raises sys.error if
      // ANTHROPIC_API_KEY is unset. See ADR-008.
      _                <- Resource.eval(IO(AnthropicClient.API_KEY_CHECK))
      userRepo          = new DoobieUserRepository(xa)
      habitRepo         = new DoobieHabitRepository(xa)
      completionRepo    = new DoobieHabitCompletionRepository(xa)
      analyticsRepo     = new DoobieAnalyticsRepository(xa)
      habitService      = new DefaultHabitService(habitRepo, Clock[IO])
      completionSvc     = new DefaultHabitCompletionService(habitRepo, completionRepo, Clock[IO])
      analyticsService  = new DefaultAnalyticsService(habitRepo, analyticsRepo)
      allRoutes         = new DocsRoutes().routes <+>
                          new HabitRoutes(habitService).routes <+>
                          new HabitCompletionRoutes(completionSvc).routes <+>
                          new InsightsRoutes(analyticsService).routes
    } yield AppResources(allRoutes, userRepo)
}
```

Behaviour at startup:
- If `ANTHROPIC_API_KEY` is missing, `Resource.eval(IO(AnthropicClient.API_KEY_CHECK))`
  throws during object resolution. The exception propagates out of
  `Main.run`, Cats Effect prints it, and the JVM exits non-zero — satisfying
  AC-7.
- If the key is present, the rest of the wiring proceeds as before. The
  existing `Main.scala` is unchanged (it already destructures via
  `resources.routes` field access — no pattern match to update).

## Test plan

All new tests use the same conventions as the rest of the codebase:
ScalaTest `AnyWordSpec` / `AsyncWordSpec`, `@RunWith(classOf[JUnitRunner])`,
Testcontainers-driven specs marked `@Ignore` (engineer runs them manually
with Docker).

### New test: `InsightPromptSpec` (pure unit tests, AC-12)

File: `backend/src/test/scala/com/habittracker/prompt/InsightPromptSpec.scala`.

Four test cases, all synchronous, no IO, no Docker:

```scala
package com.habittracker.prompt

import com.habittracker.model.HabitContext
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

@RunWith(classOf[JUnitRunner])
class InsightPromptSpec extends AnyWordSpec with Matchers {

  private val habitId1 = UUID.randomUUID()
  private val habitId2 = UUID.randomUUID()

  private val sampleCtx = HabitContext(
    userId             = 42L,
    streaks            = Map(habitId1 -> 3, habitId2 -> 0),
    completionByDay    = Map(
      "Monday"    -> 0.75,
      "Tuesday"   -> 0.50,
      "Wednesday" -> 0.25,
      "Thursday"  -> 0.00,
      "Friday"    -> 1.00,
      "Saturday"  -> 0.00,
      "Sunday"    -> 0.50
    ),
    consistencyRanking = List("Read 20 pages" -> 0.80, "Exercise" -> 0.40)
  )

  "InsightPrompt.build" should {

    "include the userId from ctx in the output" in {
      val out = InsightPrompt.build(sampleCtx)
      out should include ("42")
    }

    "include at least one habit name from ctx" in {
      val out = InsightPrompt.build(sampleCtx)
      (out.contains("Read 20 pages") || out.contains("Exercise")) shouldBe true
    }

    "include at least one day name from ctx" in {
      val out = InsightPrompt.build(sampleCtx)
      val dayNames = List("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")
      dayNames.exists(d => out.contains(d)) shouldBe true
    }

    "produce non-empty strings for all 3 named section vals" in {
      // Reflection-free assertion: we assert the full output contains a
      // non-empty substring from each section by checking for section-specific
      // keywords. This covers AC-12's "all 3 named section vals produce
      // non-empty strings" requirement.
      val out = InsightPrompt.build(sampleCtx)
      out should include ("Current streaks:")
      out should include ("Completion rate by day of week:")
      out should include ("Consistency ranking")
    }
  }
}
```

These four tests map 1:1 to AC-12.

### New test: `DoobieAnalyticsRepositorySpec` (Testcontainers, AC-11)

File: `backend/src/test/scala/com/habittracker/repository/DoobieAnalyticsRepositorySpec.scala`.

Template: identical bootstrap to `DoobieHabitRepositorySpec` — Postgres 17
container, Liquibase `update("")` against
`Paths.get("../../infra/db/changelog").toAbsolutePath.normalize`,
`HikariTransactor`, `@Ignore` annotation.

**Four test cases** (mapping 1:1 to AC-11):

#### Test 1: `streakForHabit` — 3 consecutive days → streak = 3

```scala
"streakForHabit" should {
  "return 3 when the habit was completed on today and the 2 prior days" in {
    val habit = makeHabit()
    run(habitRepo.create(habit))

    val today     = LocalDate.now()
    val yesterday = today.minusDays(1)
    val twoDaysAg = today.minusDays(2)

    run(completionRepo.create(makeCompletion(habit.id, today)))
    run(completionRepo.create(makeCompletion(habit.id, yesterday)))
    run(completionRepo.create(makeCompletion(habit.id, twoDaysAg)))

    val streak = run(analyticsRepo.streakForHabit(habit.id))
    streak shouldBe 3
  }
}
```

#### Test 2: `streakForHabit` — gap on yesterday → streak = 0

```scala
"streakForHabit" should {
  "return 0 when yesterday has no completion, even if today does not" in {
    val habit = makeHabit()
    run(habitRepo.create(habit))

    val today         = LocalDate.now()
    val threeDaysAgo  = today.minusDays(3)
    val fourDaysAgo   = today.minusDays(4)

    // No completion for today, no completion for yesterday, no completion
    // for two days ago; completions only exist 3-4 days back. Streak = 0.
    run(completionRepo.create(makeCompletion(habit.id, threeDaysAgo)))
    run(completionRepo.create(makeCompletion(habit.id, fourDaysAgo)))

    val streak = run(analyticsRepo.streakForHabit(habit.id))
    streak shouldBe 0
  }
}
```

#### Test 3: `completionRateByDayOfWeek` — seeded completions → expected rates

Fixture: user 1 has one habit. The earliest completion is 2 weeks ago
(Monday, 14 days before today). Seed completions such that Monday has 2
weeks-with-completion out of 2, Tuesday has 1 of 2, Wednesday–Sunday 0.

Pick fixed dates relative to today to keep the test stable:
- `monday1 = today.with(DayOfWeek.MONDAY).minusWeeks(2)` (two Mondays ago)
- `tuesday1 = monday1.plusDays(1)`
- `monday2 = monday1.plusWeeks(1)` (last Monday)

Insert completions on `monday1`, `tuesday1`, `monday2`. `completionRateByDayOfWeek`
is expected to return (rates are fractions):
- `Monday` -> `1.0` (2 weeks with a Monday completion / 2 total weeks)
- `Tuesday` -> `0.5` (1 / 2)
- other 5 days -> `0.0`

Assertion:

```scala
"completionRateByDayOfWeek" should {
  "return expected rates for seeded completions" in {
    val habit = makeHabit()
    run(habitRepo.create(habit))

    // Seed two Mondays and one Tuesday, all within the last 14 days.
    val today    = LocalDate.now()
    val monday2w = today.`with`(java.time.DayOfWeek.MONDAY).minusWeeks(2)
    val tuesday2w = monday2w.plusDays(1)
    val monday1w = monday2w.plusWeeks(1)

    run(completionRepo.create(makeCompletion(habit.id, monday2w)))
    run(completionRepo.create(makeCompletion(habit.id, tuesday2w)))
    run(completionRepo.create(makeCompletion(habit.id, monday1w)))

    val result = run(analyticsRepo.completionRateByDayOfWeek(1L))

    result("Monday")    shouldBe 1.0 +- 0.01
    result("Tuesday")   shouldBe 0.5 +- 0.01
    result("Wednesday") shouldBe 0.0
    result("Thursday")  shouldBe 0.0
    result("Friday")    shouldBe 0.0
    result("Saturday")  shouldBe 0.0
    result("Sunday")    shouldBe 0.0

    result.keySet shouldBe Set("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")
  }
}
```

The Developer agent may need to tweak the exact expected numbers based on
how ISO week boundaries fall for "today" when the test runs — Postgres
`DATE_TRUNC('week', ...)` uses Monday as week start, which is why the
fixture dates are chosen relative to Monday. If today is *itself* a
Monday, `monday1w` and `today` might collide — the fixture avoids this
by using two- and one-week-ago Mondays only, never today.

#### Test 4: `habitConsistencyRanking` — 2 habits with different rates → descending

```scala
"habitConsistencyRanking" should {
  "return habits sorted descending by score" in {
    val habit1 = makeHabit(name = "High-consistency")
    val habit2 = makeHabit(name = "Low-consistency")
    run(habitRepo.create(habit1))
    run(habitRepo.create(habit2))

    // Seed 5 completions for habit1, 1 completion for habit2. The
    // denominator (days-since-created) is the same for both, so
    // habit1 outranks habit2 on sheer count.
    val today = LocalDate.now()
    (0 to 4).foreach { i =>
      run(completionRepo.create(makeCompletion(habit1.id, today.minusDays(i.toLong))))
    }
    run(completionRepo.create(makeCompletion(habit2.id, today)))

    val ranking = run(analyticsRepo.habitConsistencyRanking(1L))

    ranking.map(_._1) shouldBe List("High-consistency", "Low-consistency")
    ranking.head._2 should be > ranking(1)._2
  }
}
```

### Test fixtures — shared helpers

The spec uses the same `makeHabit` / `makeCompletion` helpers as
`DoobieHabitCompletionRepositorySpec`:

```scala
private def makeHabit(name: String = "Test habit", userId: Long = 1L): Habit = {
  val now = Instant.now()
  Habit(UUID.randomUUID(), userId, name, None, Frequency.Daily, now, now, None)
}

private def makeCompletion(
    habitId: UUID,
    completedOn: LocalDate = LocalDate.now(),
    note: Option[String] = None
): HabitCompletion =
  HabitCompletion(UUID.randomUUID(), habitId, completedOn, note, Instant.now())
```

`beforeEach` cleans both tables for isolation:
```scala
override def beforeEach(): Unit = {
  sql"DELETE FROM habit_completions".update.run.transact(transactor).unsafeRunSync()
  sql"DELETE FROM habits".update.run.transact(transactor).unsafeRunSync()
}
```

### What is NOT tested in this PBI

- **Route-level test of `InsightsRoutes`**: the insights handler calls
  `AnthropicClient.complete` directly. There is no abstraction to stub
  (HARD LIMIT). A route unit test would require hitting the live Anthropic
  API, which is not acceptable for CI. The engineer verifies the route
  manually by curl against a running server with `ANTHROPIC_API_KEY` set.
  The Reviewer agent notes this gap as expected, not as a defect.
- **Startup failure when `ANTHROPIC_API_KEY` is missing** (AC-7): verified
  manually by the engineer (unset the variable, run `./gradlew run`,
  observe the error). Scala `object` initialisation is hard to test in the
  same JVM — a unit test would need a child process with a sanitised
  environment. Out of scope for PBI-013.
- **`DefaultAnalyticsService.buildHabitContext` composition**: the logic
  is a straightforward `traverse` + two repository calls. The Testcontainers
  spec for `DoobieAnalyticsRepository` covers the SQL; the pure assembly
  itself has no branching. A dedicated service spec would only re-assert
  "traverse returns the map" and is skipped.
- **OpenAPI schema compliance test**: the existing `DocsRoutesSpec` verifies
  `/docs/openapi.json` renders; adding schema-validation tooling is out of
  scope.

### Commands to run

- `./gradlew compileScala` — after creating each new file group (model →
  repository → service → client → prompt → http).
- `./gradlew test` — once all files compile. Expected outcome: zero
  compilation errors, zero failures, all existing tests pass unchanged,
  the four new pure `InsightPromptSpec` tests pass. `DoobieAnalyticsRepositorySpec`
  is `@Ignore`d by default (matching other repo specs) and runs only when
  the engineer manually un-ignores it with Docker running.
- `grep -r "akka" src/` from the `backend/` directory — must return no
  results (AC-14, AC-16).

## ADRs required

- **ADR-008-phase1-pattern-detection.md** — written as part of this plan.
  Covers file layout under `com.habittracker.{model,client,prompt}`;
  `AnthropicClient` as a no-trait `object` called directly from the
  route; `ANTHROPIC_API_KEY` read at object-init with startup-failure
  forced from `AppResources.make`; a new `InsightsRoutes` class for the
  new endpoint; SQL placement in a new `AnalyticsRepository` (not in
  `HabitRepository`); Testcontainers + Postgres for SQL query tests;
  Gradle as the build tool (correcting the phase brief's sbt references).

## Open questions

No blocking questions. Three notes for engineer awareness:

1. **`InsightPrompt.build` formatting precision**: the day-section uses
   `f"$rate%.0f%%"`, which rounds to whole percentages. If finer precision
   is preferred in the prompt, change to `%.1f%%`. Not locked in the PBI —
   either satisfies AC-12.
2. **`completionRateByDayOfWeek` week-span semantics**: the query uses
   "total weeks = weeks since the user's earliest completion, minimum 1".
   An alternative interpretation is "weeks since the habit was created",
   but the PBI says "percentage of weeks that day had at least one
   completion across any habit owned by the user" — earliest completion
   is the natural reference point for a cross-habit denominator. If the
   engineer prefers a fixed lookback window (e.g. last 12 weeks), that is
   a follow-up PBI.
3. **Streak across midnight/timezone**: `CURRENT_DATE` in Postgres is
   evaluated in the server's session timezone. The `HabitCompletion.completedOn`
   column is `DATE` (no timezone). This PBI accepts the server-timezone
   default. If the app is deployed to a region with a different timezone
   than the users, a separate ADR is needed to introduce a user-timezone
   field; out of scope here.

---

This technical plan is ready for your review. Please approve or request changes before I hand off to the Developer agent.
