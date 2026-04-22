---
name: DocsRoutes implementation notes
description: Key patterns used in DocsRoutes.scala for serving OpenAPI spec and Swagger UI
type: project
---

DocsRoutes serves GET /docs, /docs/openapi.yaml, /docs/openapi.json, and /docs/ui/{file}.

**Why:** PLAN-007 adds API documentation as a discoverable endpoint.

**How to apply:** When extending or modifying docs endpoints, note these patterns:
- SnakeYAML (org.yaml:snakeyaml, transitive via liquibase-core) is used for YAML→Circe JSON conversion; do not add circe-yaml as a dependency.
- `application/x-yaml` ContentType is built with `MediaType.customWithFixedCharset("application", "x-yaml", HttpCharsets.UTF-8)`.
- SwaggerUI WebJar path pattern: `META-INF/resources/webjars/swagger-ui/5.17.14/{file}`.
- `specYamlBytes` and `specJsonString` are lazy vals (loaded once on first access).
- The `~` combinator for routes requires `import akka.http.scaladsl.server.Directives._` — this was added to both Main.scala and HabitApiIntegrationSpec.scala.
- `scala.util.Try` must not be imported in DocsRoutes.scala (not used; `-Ywarn-unused:imports` is active).
