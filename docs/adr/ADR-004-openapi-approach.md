# ADR-004: OpenAPI spec production — hand-authored YAML with WebJar-served Swagger UI

## Status
Accepted

## Context
PBI-007 requires the backend to expose:

- `GET /docs` — a Swagger UI rendered in the browser.
- `GET /docs/openapi.yaml` (and/or `.json`) — a machine-readable OpenAPI 3.x
  specification covering all five existing Habit CRUD endpoints.
- An integration test that verifies the spec endpoint returns `200` with the
  correct `Content-Type`.

The decision is **how the spec itself is produced** on top of Akka HTTP 10.5.3 /
Scala 2.13, and **how Swagger UI assets are served**. Three options were
evaluated:

### Option 1 — Hand-authored YAML/JSON, served as a static classpath resource
A single `openapi.yaml` file lives under `src/main/resources/openapi/`. An Akka
HTTP route (`getFromResource`) serves it verbatim at `GET /docs/openapi.yaml`.
Swagger UI is served either from a WebJar on the classpath
(`org.webjars:swagger-ui`) via `getFromResource`, or from a single hand-written
HTML file that loads Swagger UI's JS/CSS from a public CDN.

- **Library availability:** WebJar `org.webjars:swagger-ui` is published to
  Maven Central, widely used, and independent of the HTTP framework. No Scala
  binary version coupling.
- **Implementation complexity:** Low. Two routes and one YAML file.
- **Drift risk:** Medium. The spec and the DTOs must be kept in sync by
  convention. Integration tests can mitigate drift but cannot fully prevent it.
- **Maintenance:** The spec grows linearly with endpoints. For the five
  endpoints in scope, the YAML is on the order of ~150 lines.

### Option 2 — Code-first generation (tapir or swagger-akka-http)
- **`tapir`** would be idiomatic but requires **rewriting all existing route
  definitions** as `Endpoint[..]` values and switching the route interpreter
  from the current Akka HTTP `Route` DSL to `AkkaHttpServerInterpreter`. Tapir's
  Akka HTTP module continues to publish for Scala 2.13 (module rename to
  `sttp-tapir` occurred in the 1.x line), but adopting it means replacing
  `HabitRoutes.scala`, `HabitCodecs.scala` usage patterns, and the
  `ErrorHandler` wiring — a large, disruptive change for a PoC whose routes
  are already written, tested, and working. Tapir also introduces a new effect
  binding layer (`Future` vs `IO`) on top of the one already documented in
  ADR-001.
- **`swagger-akka-http`** (the `com.github.swagger-akka-http:swagger-akka-http`
  module) drives spec generation from Swagger/OpenAPI JAX-RS style annotations
  (`@Operation`, `@ApiResponse`, `@Parameter`) placed on route-producing
  classes. It is still published for Scala 2.13 and is compatible with Akka
  HTTP 10.5.x. The downside is that annotations become a second source of
  truth alongside the `Route` DSL: the annotations describe a shape the
  compiler cannot check against the actual routes, so drift between
  annotations and routes is silent. In practice this library ends up writing
  almost as much boilerplate as hand-authoring YAML — just in a different
  syntax and with weaker review ergonomics.

- **Library availability:** Both publish for Scala 2.13. Tapir's Akka HTTP
  binding is stable; `swagger-akka-http` tracks Akka HTTP closely.
- **Implementation complexity:** High (tapir — rewrite existing routes) or
  medium (swagger-akka-http — annotate existing routes, add a spec-assembly
  object).
- **Drift risk:** Low (tapir — routes and spec are generated from the same
  values) or medium (swagger-akka-http — annotations decouple from routes).
- **Maintenance:** Tapir shifts the PoC's architecture; swagger-akka-http adds
  a dependency whose annotation model is idiosyncratic and not widely known on
  this team.

### Option 3 — Hybrid: hand-authored spec merged with a generated fragment
Produce the DTO schema section from Circe codecs (via a third-party Circe →
JSON-Schema library) and hand-author paths, parameters, and responses on top.
No well-maintained Circe-to-OpenAPI-schema library exists for Scala 2.13 that
is not part of a larger framework (tapir) — options like
`circe-json-schema` are limited and would need bespoke glue. The marginal
benefit over plain hand-authoring is small for five endpoints, and the
maintenance surface is higher (extra dependency + custom merge step).

### Selection criteria
- **Scope of change to existing, working code.** PBI-007 explicitly documents
  the five existing endpoints; it does not authorise a route-layer rewrite.
- **Library availability on Maven Central for Scala 2.13 + Akka HTTP 10.5.x.**
  All three options are viable on this axis. Not the deciding factor.
- **Drift risk in a five-endpoint PoC.** With five endpoints, drift is
  manageable by convention and by a focused integration test that asserts
  the presence of each path in the served spec. A large generated-spec
  apparatus is overkill.
- **Learnability / reviewability.** OpenAPI YAML is a well-known,
  declarative artefact; any web developer can open the file in a PR and
  review what the API will look like without understanding Scala macros or
  annotation processors.
- **PoC budget.** This is an upskilling project, not a production product.
  The architect should not introduce new frameworks unless they deliver value
  that cannot be obtained cheaply another way.

## Decision

### Spec production
**Hand-author a single `openapi.yaml` file** checked into
`backend/src/main/resources/openapi/openapi.yaml`. The file declares OpenAPI
3.0.3, covers all five existing endpoints with full request and response
schemas (including the `frequency` enum and the shared `ErrorResponse`
component), and serves as the single source of truth for what the API looks
like from the outside.

### Serving the spec
Add a new route class `DocsRoutes` (Akka HTTP `Route`) that:

- Serves `GET /docs/openapi.yaml` by reading the YAML file from the classpath
  via `getFromResource("openapi/openapi.yaml", MediaTypes.`application/x-yaml`)`.
- Serves `GET /docs/openapi.json` by reading the same YAML file, parsing it
  with SnakeYAML (already a transitive dependency of `liquibase-core` per
  ADR-003), and re-emitting it as JSON using Circe. Both formats satisfy the
  PBI; offering both costs very little and is friendlier for tool
  integrations.
- Serves `GET /docs` by returning a small hand-written HTML page (checked in
  at `src/main/resources/openapi/index.html`) that loads Swagger UI's
  JavaScript and CSS from the `org.webjars:swagger-ui` WebJar
  (Maven-Central-published, version 5.17.14) via the `/webjars/...` path
  prefix.
- Serves the WebJar assets by mounting `getFromResource("META-INF/resources/webjars/swagger-ui/5.17.14/{file}")`
  under the `/docs/ui/` path prefix so that the HTML page can reference
  `/docs/ui/swagger-ui-bundle.js`, `/docs/ui/swagger-ui.css`, etc. Pinning the
  version in the path avoids surprises if the WebJar is ever upgraded.

### Environment gating
PBI-007 permits either "gate on `APP_ENV`" or "always expose". **Decision:
always expose in every environment.** Rationale:
- The PoC has no production environment and no authentication; there is no
  attack surface to protect.
- Gating adds branching logic in `Main`, a new config flag, and a conditional
  integration test — all for a hypothetical future risk.
- When a real production environment is introduced, revisiting this is a
  three-line change plus a new ADR.

### Content-Type conventions
- `GET /docs/openapi.yaml` → `application/x-yaml` (no charset parameter; the
  type is text-based and most tooling accepts this).
- `GET /docs/openapi.json` → `application/json` (standard; Circe codec).
- `GET /docs` → `text/html; charset=UTF-8`.
- `GET /docs/ui/*` → per-asset content types, resolved from file extension by
  Akka HTTP's `ContentType` inference (`getFromResource` does this
  automatically).

## Consequences

**Easier:**
- No existing route code changes. `HabitRoutes` stays exactly as-is; the new
  docs route is mounted alongside it in `Main`.
- OpenAPI YAML is directly editable in IDEs, browsable on GitHub, and
  linter-friendly (Spectral, Redocly CLI, etc.).
- Swagger UI assets come from a widely-used WebJar, versioned in
  `build.gradle` — no CDN calls at runtime, so the UI works offline during
  development.
- Drift is caught quickly in review: any PR that adds an endpoint must also
  add a block to the YAML, and reviewers see both in the same diff.

**Harder / trade-offs:**
- **Drift is enforced only by convention.** If a developer changes the
  `HabitResponse` DTO without updating the YAML, the mismatch is silent at
  compile time. Mitigations in the PBI's integration test scope: the test
  asserts that all five path-operation pairs are present in the served spec
  and that the `Frequency` enum lists exactly `daily` and `weekly`. This
  catches *removal* and *renames* of well-known constants; it does not catch
  silent schema drift on DTO fields. Accepted for a PoC — the cost of catching
  that fully (tapir migration) is not proportional to the benefit over five
  endpoints.
- **YAML parsing adds a tiny runtime cost to `GET /docs/openapi.json`** (the
  server parses YAML and re-emits JSON on each request). The response is
  cached in a `lazy val` inside `DocsRoutes` so the parse happens once per
  JVM.
- **Swagger UI version pinning.** The WebJar path includes `5.17.14`. Bumping
  the version requires changing both the Gradle dependency and the path in
  the HTML page and the Akka HTTP route. Acceptable — this is a single
  coordinated change, and pinning protects the UI from silent behavioural
  changes in Swagger UI releases.

**Locked in:**
- The spec lives in a single YAML file. Splitting it into multiple files
  (paths in one, components in another) is possible later via `$ref`, but
  today the file is small enough that one file is clearer.
- Swagger UI assets are served from a WebJar on the classpath. Switching to a
  CDN later is straightforward but would need a new ADR (there is no CDN
  allowlist in place today; adding an outbound-dependency would be a policy
  decision).
- If the project later adopts tapir or swagger-akka-http, the YAML file will
  be retired and this ADR superseded. Until then, YAML is authoritative.

## New dependencies introduced
Flagged per CLAUDE.md:

| Dependency | Version | Purpose |
|---|---|---|
| `org.webjars:swagger-ui` | 5.17.14 | Swagger UI static assets (JS/CSS) served from the classpath under `/docs/ui/` |

No other new dependencies. SnakeYAML (`org.yaml:snakeyaml`) is already on the
classpath transitively via `liquibase-core` 4.29.2 (ADR-003) and is the YAML
parser used by `DocsRoutes` — no explicit dependency entry is required, but
the build script should keep SnakeYAML's provenance documented in a comment
to signal the assumption.
