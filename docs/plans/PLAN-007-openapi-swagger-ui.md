# PLAN-007: Serve OpenAPI Specification and Swagger UI

## PBI reference
PBI-007: Serve OpenAPI Specification and Swagger UI

## Summary
Introduce a hand-authored `openapi.yaml` checked into the backend resources,
a new `DocsRoutes` Akka HTTP route class that serves it at
`GET /docs/openapi.yaml` and `GET /docs/openapi.json`, a Swagger UI page at
`GET /docs` backed by the `org.webjars:swagger-ui` WebJar, and an integration
test that asserts the spec is reachable with the correct `Content-Type` and
contains all five existing Habit CRUD endpoints. The approach is spelled out
in ADR-004; there is no rewrite of existing routes, no new framework, and no
schema or DB change. Docs routes are always exposed — no `APP_ENV` gate — per
the decision recorded in ADR-004.

## Affected files

| File | Change type | Description |
|------|-------------|-------------|
| `backend/build.gradle` | Modify | Add a single new `implementation` dependency: `org.webjars:swagger-ui:5.17.14`. No other build changes. |
| `backend/src/main/resources/openapi/openapi.yaml` | Create | Hand-authored OpenAPI 3.0.3 spec covering all five existing Habit endpoints, the `Frequency` enum, the `CreateHabitRequest`, `UpdateHabitRequest`, `HabitResponse`, and `ErrorResponse` schemas. Single source of truth for the API contract. |
| `backend/src/main/resources/openapi/index.html` | Create | Static Swagger UI host page. Loads `/docs/ui/swagger-ui.css`, `/docs/ui/swagger-ui-bundle.js`, and `/docs/ui/swagger-ui-standalone-preset.js` from the WebJar, and configures Swagger UI to fetch `/docs/openapi.yaml`. |
| `backend/src/main/scala/com/habittracker/http/DocsRoutes.scala` | Create | New Akka HTTP route class. Exposes `GET /docs`, `GET /docs/openapi.yaml`, `GET /docs/openapi.json`, and `GET /docs/ui/{file}`. Performs a one-time YAML→JSON conversion via SnakeYAML + Circe for the JSON endpoint, cached in a `lazy val`. |
| `backend/src/main/scala/com/habittracker/Main.scala` | Modify | Instantiate `new DocsRoutes()` and concatenate its route with `habitRoutes.route` before binding — `Http().newServerAt(...).bind(habitRoutes.route ~ docsRoutes.route)`. No other change to startup flow. |
| `backend/src/test/scala/com/habittracker/http/DocsRoutesSpec.scala` | Create | Unit-level route test using `ScalatestRouteTest`. Asserts: `GET /docs/openapi.yaml` returns 200 + `application/x-yaml`; `GET /docs/openapi.json` returns 200 + `application/json` and contains the five paths and the `frequency` enum values `daily`, `weekly`; `GET /docs` returns 200 + `text/html`. |
| `backend/src/test/scala/com/habittracker/integration/HabitApiIntegrationSpec.scala` | Modify | Add one new test block: `GET /docs/openapi.json` returns `200` and `application/json`. This satisfies the PBI acceptance criterion "integration test verifies that `GET /docs/openapi.json` (or `.yaml`) returns HTTP `200` with the correct Content-Type header". Also bind `docsRoutes` into the test-time server in `beforeAll`. |
| `CLAUDE.md` | Modify (small) | Add one bullet to the "LLM integration" / "Prompt files" section's sibling, or more likely under a new "OpenAPI spec" heading, stating: "OpenAPI spec lives at `backend/src/main/resources/openapi/openapi.yaml`. Any change to a request/response DTO requires a matching edit to this file in the same PR." |

No other files are affected. No files are deleted.

## New components

- **`com.habittracker.http.DocsRoutes`** — Akka HTTP `Route` class in
  `backend/src/main/scala/com/habittracker/http/DocsRoutes.scala`. Public
  surface is a single `val route: Route`.
- **`openapi/openapi.yaml`** — new classpath resource under
  `backend/src/main/resources/openapi/`. Authoritative API spec.
- **`openapi/index.html`** — new classpath resource (same directory) that
  bootstraps Swagger UI.

## API contract

Three new endpoints. No existing endpoints change.

### GET /docs
- **Method / path:** `GET /docs`
- **Request body:** none
- **Query parameters:** none
- **Response 200:**
  - `Content-Type: text/html; charset=UTF-8`
  - Body: the HTML bytes from `openapi/index.html`. That page loads Swagger UI
    from `/docs/ui/*` and points it at `/docs/openapi.yaml`.
- **Other responses:** none (trailing slash `/docs/` is not required to be
  handled separately; Akka HTTP's `path("docs")` directive matches
  `/docs` exactly, which is sufficient).

### GET /docs/openapi.yaml
- **Method / path:** `GET /docs/openapi.yaml`
- **Request body:** none
- **Query parameters:** none
- **Response 200:**
  - `Content-Type: application/x-yaml`
  - Body: verbatim bytes from `openapi/openapi.yaml`.

### GET /docs/openapi.json
- **Method / path:** `GET /docs/openapi.json`
- **Request body:** none
- **Query parameters:** none
- **Response 200:**
  - `Content-Type: application/json`
  - Body: the same OpenAPI document as the YAML endpoint, converted to JSON.
    Conversion is done once at first request and cached in a `lazy val`.
    Subsequent requests return the cached JSON string without re-parsing.

### GET /docs/ui/{file}
- **Method / path:** `GET /docs/ui/{file}` where `{file}` is a Swagger UI
  asset name (e.g. `swagger-ui.css`, `swagger-ui-bundle.js`,
  `swagger-ui-standalone-preset.js`, `favicon-32x32.png`).
- **Request body:** none
- **Response 200:**
  - `Content-Type:` inferred by Akka HTTP from the file extension
    (`.css` → `text/css`, `.js` → `application/javascript`, `.png` →
    `image/png`).
  - Body: the asset bytes from the classpath path
    `META-INF/resources/webjars/swagger-ui/5.17.14/{file}`.
- **Response 404:** if the asset name does not resolve to a classpath
  resource (Akka HTTP `getFromResource` handles this natively).

### OpenAPI document contents (required by PBI-007)
The hand-authored `openapi.yaml` must contain, at minimum:

- `openapi: 3.0.3`
- `info`: `title: Habit Tracker API`, `version: 0.1.0`,
  `description: CRUD API for user habits (ASDLC PoC).`
- `servers`: a single entry `url: http://localhost:8080`.
- `paths`: exactly the five paths `/habits`, `/habits/{id}`, each with the
  correct methods (`post`, `get`, `get`, `put`, `delete`).
- **For every operation:**
  - `summary` (one-line human description)
  - `parameters` where applicable (the `id: UUID` path parameter on the
    three `/habits/{id}` operations, typed as `string` with
    `format: uuid`)
  - `requestBody` referencing `#/components/schemas/CreateHabitRequest` (on
    `POST /habits`) or `#/components/schemas/UpdateHabitRequest` (on
    `PUT /habits/{id}`)
  - `responses` for every status code the current route can return:
    - `POST /habits`: `201` → `HabitResponse`; `400` → `ErrorResponse`
    - `GET /habits`: `200` → array of `HabitResponse`; `500` →
      `ErrorResponse`
    - `GET /habits/{id}`: `200` → `HabitResponse`; `400` →
      `ErrorResponse` (malformed UUID); `404` → `ErrorResponse`
    - `PUT /habits/{id}`: `200` → `HabitResponse`; `400` →
      `ErrorResponse` (validation or malformed UUID); `404` →
      `ErrorResponse`
    - `DELETE /habits/{id}`: `204` (no body); `400` → `ErrorResponse`
      (malformed UUID); `404` → `ErrorResponse`
- `components.schemas`:
  - `Frequency`: `type: string`, `enum: [daily, weekly]`
  - `CreateHabitRequest`: object with `name: string (required)`,
    `description: string (nullable, optional)`, `frequency: $ref Frequency
    (required)`
  - `UpdateHabitRequest`: identical shape to `CreateHabitRequest` (all three
    fields; description nullable)
  - `HabitResponse`: object with `id: string, format: uuid`,
    `name: string`, `description: string (nullable)`,
    `frequency: $ref Frequency`, `createdAt: string, format: date-time`,
    `updatedAt: string, format: date-time`. All `required` except
    `description`.
  - `ErrorResponse`: object with a single required `message: string`.
- No `securitySchemes` section (the API has no authentication today).

## Database changes

None. PBI-007 is doc-only; no schema changes, no new Liquibase changeset.

## LLM integration

None. PBI-007 does not touch any LLM flow, prompt file, or RAG logic.

## Implementation notes — `DocsRoutes.scala` shape

The route class should follow the same style as `HabitRoutes`. Sketch for the
Developer agent (not prescriptive to the character, but the shape is
load-bearing):

```scala
package com.habittracker.http

import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, MediaType, MediaTypes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import io.circe.yaml.parser.{parse => parseYaml}  // NOTE: we do NOT add circe-yaml; see below
import io.circe.syntax._

import scala.io.Source

final class DocsRoutes extends JsonSupport {

  private val SpecResourcePath      = "openapi/openapi.yaml"
  private val IndexResourcePath     = "openapi/index.html"
  private val SwaggerUiVersion      = "5.17.14"
  private val SwaggerUiResourceBase =
    s"META-INF/resources/webjars/swagger-ui/$SwaggerUiVersion"

  // Load YAML once at class init, convert to JSON once, cache both strings.
  private lazy val specYamlBytes: Array[Byte] =
    readClasspathBytes(SpecResourcePath)

  private lazy val specJsonString: String = {
    val yamlText = new String(specYamlBytes, "UTF-8")
    val yaml     = new org.yaml.snakeyaml.Yaml()
    val loaded   = yaml.load[Any](yamlText)
    // Convert java.util.Map / List / primitives to io.circe.Json, then render.
    snakeYamlToCirce(loaded).noSpaces
  }

  private val yamlContentType: ContentType =
    MediaType
      .customWithFixedCharset("application", "x-yaml", HttpCharsets.`UTF-8`)
      .toContentType

  val route: Route =
    pathPrefix("docs") {
      concat(
        pathEndOrSingleSlash {
          get {
            val html = new String(readClasspathBytes(IndexResourcePath), "UTF-8")
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, html))
          }
        },
        path("openapi.yaml") {
          get {
            complete(HttpEntity(yamlContentType, specYamlBytes))
          }
        },
        path("openapi.json") {
          get {
            complete(HttpEntity(ContentTypes.`application/json`, specJsonString))
          }
        },
        pathPrefix("ui") {
          path(Segment) { file =>
            getFromResource(s"$SwaggerUiResourceBase/$file")
          }
        }
      )
    }
}
```

Notes for the Developer:

1. **YAML → JSON conversion.** Do **not** add a new `circe-yaml`
   dependency. SnakeYAML is already on the classpath transitively via
   `liquibase-core` (ADR-003). Implement a small private helper
   `snakeYamlToCirce(Any): io.circe.Json` that walks the `java.util.Map`,
   `java.util.List`, `String`, `java.lang.Number`, `java.lang.Boolean`,
   and `null` tree that SnakeYAML produces, and builds an `io.circe.Json`.
   ~30 lines. Flag this helper in the diff so the Reviewer can check it.
2. **Classpath byte reads.** Use
   `getClass.getClassLoader.getResourceAsStream(path)` with
   `Resource.fromAutoCloseable` or a try/finally. Return `Array[Byte]` via
   `.readAllBytes()` on JDK 11.
3. **Content type for YAML.** `application/x-yaml` is not in Akka HTTP's
   predefined `ContentTypes`; construct it with
   `MediaType.customWithFixedCharset("application", "x-yaml", HttpCharsets.`UTF-8`).toContentType`.
4. **`index.html` contents.** Use this template (commit as-is):

   ```html
   <!DOCTYPE html>
   <html lang="en">
   <head>
     <meta charset="UTF-8">
     <title>Habit Tracker API — Swagger UI</title>
     <link rel="stylesheet" type="text/css" href="/docs/ui/swagger-ui.css" />
   </head>
   <body>
     <div id="swagger-ui"></div>
     <script src="/docs/ui/swagger-ui-bundle.js"></script>
     <script src="/docs/ui/swagger-ui-standalone-preset.js"></script>
     <script>
       window.onload = function() {
         window.ui = SwaggerUIBundle({
           url: "/docs/openapi.yaml",
           dom_id: "#swagger-ui",
           presets: [
             SwaggerUIBundle.presets.apis,
             SwaggerUIStandalonePreset
           ],
           layout: "StandaloneLayout"
         });
       };
     </script>
   </body>
   </html>
   ```

5. **Route composition in `Main.scala`.** The single-line change is:

   ```scala
   val habitRoutes = new HabitRoutes(service)
   val docsRoutes  = new DocsRoutes()
   Http().newServerAt(...).bind(habitRoutes.route ~ docsRoutes.route)
   ```

   The `~` combinator is the Akka HTTP `RouteConcatenation` operator (already
   imported via `Directives._` in `HabitRoutes`; `Main.scala` will need
   `import akka.http.scaladsl.server.Directives._` added).
6. **`openapi.yaml` authoring.** The Developer must hand-write the full
   spec. The Reviewer should manually open `/docs` in a browser before
   approving, to confirm Swagger UI renders all five operations without
   spec-parse errors.

## Test plan

### New unit test — `DocsRoutesSpec`
Location: `backend/src/test/scala/com/habittracker/http/DocsRoutesSpec.scala`.
Style: `ScalatestRouteTest` + `AnyWordSpec`, mirroring `HabitRoutesSpec`.

Assertions (one `in` clause each):

1. `GET /docs/openapi.yaml` returns `200` and `Content-Type` starts with
   `application/x-yaml`.
2. `GET /docs/openapi.yaml` body, when parsed as YAML, contains a top-level
   `openapi` key whose value starts with `"3."`.
3. `GET /docs/openapi.json` returns `200` and `Content-Type` is
   `application/json`.
4. `GET /docs/openapi.json` body, when parsed with Circe, contains:
   - the five path entries: `/habits` and `/habits/{id}`
   - methods `post` and `get` under `/habits`
   - methods `get`, `put`, `delete` under `/habits/{id}`
   - `components.schemas.Frequency.enum == ["daily", "weekly"]`
   - `components.schemas.ErrorResponse` has a required `message` field
5. `GET /docs` returns `200` and `Content-Type` starts with `text/html`.
6. `GET /docs/ui/swagger-ui.css` returns `200` (validates the WebJar is
   wired correctly; no body-content assertion needed).

### Existing integration test — one new block
Location: `backend/src/test/scala/com/habittracker/integration/HabitApiIntegrationSpec.scala`.

- In `beforeAll`, add `docsRoutes = new DocsRoutes()` and change the
  server-binding line to
  `bind(routes.route ~ docsRoutes.route)`.
- Add one new `"/docs"` `should` block with a single `in` clause:
  `"GET /docs/openapi.json returns 200 and application/json"`. Assert
  status is `200` and `Content-Type` is `application/json`. This is the
  PBI's mandatory integration test acceptance criterion.

The three Docker-free tests in `DocsRoutesSpec` will run in every
`gradle test` invocation (not gated by `-PskipDockerTests`). The one new
integration-spec block runs only when Docker is available.

### Out of scope for this PBI's tests
- "Try it out" interactive request flows in Swagger UI (PBI explicitly lists
  these as out of scope).
- Cross-spec drift detection (e.g. reflecting Scala DTOs at runtime and
  comparing to the YAML). This is the principal trade-off of the hand-authored
  approach and is documented in ADR-004.

## ADRs required

- **ADR-004 — OpenAPI spec production: hand-authored YAML with WebJar-served
  Swagger UI.** Written alongside this plan at
  `docs/adr/ADR-004-openapi-approach.md`.

## New dependencies — explicit call-out (per CLAUDE.md)

This plan introduces exactly one new dependency. **Engineer must approve
before the Developer agent starts:**

| Dependency | Version | Purpose |
|---|---|---|
| `org.webjars:swagger-ui` | 5.17.14 | Static Swagger UI JS/CSS assets served at `/docs/ui/*` |

- Published to Maven Central.
- No Scala binary version coupling (pure static resources inside a JAR).
- No transitive dependencies of note; the WebJar is a small asset package.

**No other new dependencies.** SnakeYAML is already available transitively via
`liquibase-core:4.29.2` (ADR-003). Circe, Akka HTTP, and `akka-http-circe` are
all already declared. `io.circe.yaml` is **not** being added — the Developer
will use SnakeYAML directly for YAML parsing and Circe for JSON emission.

## Implementation order for the Developer agent

Execute in this order. Each step should leave the build green before moving
to the next.

1. **Add the WebJar dependency** in `backend/build.gradle` (one line under
   `dependencies`). Run `gradle compileScala` to confirm resolution.
2. **Create `openapi.yaml`** under `backend/src/main/resources/openapi/`.
   Hand-author the full spec per the "OpenAPI document contents" section of
   this plan. Validate it by pasting into
   [editor.swagger.io](https://editor.swagger.io) before proceeding (this is a
   manual developer step — no automation required).
3. **Create `index.html`** under the same directory, using the template
   provided above.
4. **Create `DocsRoutes.scala`** with the four routes and the
   SnakeYAML-to-Circe helper. Implement `specJsonString` lazily.
5. **Wire `DocsRoutes` into `Main.scala`** using the `~` combinator. Run
   `gradle run` and manually visit `http://localhost:8080/docs` in a
   browser — Swagger UI should render all five endpoints. Also confirm
   `curl http://localhost:8080/docs/openapi.json | jq .openapi` returns
   `"3.0.3"`.
6. **Write `DocsRoutesSpec.scala`** and run `gradle test` — the spec and the
   existing test suite must both pass.
7. **Extend `HabitApiIntegrationSpec`** with the new `/docs` block and update
   `beforeAll` to bind the docs route. Run the spec once locally with
   `@Ignore` removed to confirm the new assertion passes; re-apply `@Ignore`
   before committing.
8. **Update `CLAUDE.md`** with the one-paragraph "OpenAPI spec" note (see the
   "Affected files" row above for exact wording guidance).
9. Final check: `gradle test` (green) and `gradle run` + browser-visit
   `/docs` (renders).

## Open questions

1. **Should `GET /docs/openapi.yaml` serve `application/x-yaml` or
   `application/yaml` as the `Content-Type`?** Neither is formally
   registered with IANA (the RFC 9512 registration for `application/yaml`
   was published in 2024 but adoption is still uneven). The plan chooses
   `application/x-yaml` because it is the older, more widely-recognised
   variant that Swagger UI, openapi-cli, and IDEs handle without special
   configuration. Engineer to confirm — a one-line change if you prefer
   the newer `application/yaml`.
2. **Should the integration test assert the exact `application/json`
   content type, or just a prefix match (`starts with "application/json"`)?**
   Akka HTTP appends `; charset=UTF-8` for JSON by default. The
   `DocsRoutesSpec` unit test in this plan uses a prefix match to stay
   forgiving of the charset suffix. Engineer to confirm the integration
   test should do the same.
3. **Swagger UI version pinning.** The plan picks `5.17.14` (latest stable
   at 2026-04 on Maven Central). Engineer can choose to bump to the newest
   release at implementation time — this is a one-line change in
   `build.gradle` plus a corresponding change to the path constant in
   `DocsRoutes.scala` and, in the HTML, the `/docs/ui/` paths are
   version-agnostic so no HTML change is needed.
4. **Should `POST /habits` also document the `500` response?** The existing
   `ErrorHandler` returns `500 + ErrorResponse` for any unhandled exception.
   The PBI does not explicitly require `500` to be documented. The plan
   includes `500` only on `GET /habits` where the PBI's contract already
   documents that the server may fail under load; on the other endpoints
   the Developer can include `500` or omit it at their discretion.
   Engineer to confirm preference for "document 500 everywhere" vs
   "only where the PBI was explicit".

---

This technical plan is ready for your review. Please approve or request
changes before I hand off to the Developer agent.
