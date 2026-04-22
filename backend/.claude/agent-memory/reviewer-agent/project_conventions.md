---
name: Codebase conventions discovered in review
description: Patterns and conventions found in the code that are not yet in CLAUDE.md
type: project
---

## Akka HTTP route style (from HabitRoutes.scala, DocsRoutes.scala)
- Route classes are `final class` with a single public `val route: Route`
- `concat(...)` is used for sibling route alternatives, not `~` inside route bodies
- Error handling is wired via `handleExceptions` + `handleRejections` at the top
  of HabitRoutes only; DocsRoutes intentionally omits this because it has no
  service calls that can produce AppErrors

## DTO placement
- Request/response DTOs live in `com.habittracker.http.dto`
- `ErrorResponse` lives in `com.habittracker.http` (not `dto`) — one level up

## Frequency domain model
- `Frequency` is a sealed trait with `Daily`, `Weekly`, and `Custom(value)` cases
- The `Custom` case is not documented in the OpenAPI spec (only daily/weekly are
  valid from the API surface, but the domain model allows it)
- `Frequency.parse` rejects unknown values with a Left

## Classpath resource loading convention (DocsRoutes.scala)
- Resources loaded via `getClass.getClassLoader.getResourceAsStream(path)` with
  explicit null check and try/finally for stream close
- `readAllBytes()` used (requires JDK 11, which is the project target)

## SnakeYAML availability
- SnakeYAML is a transitive dependency of `liquibase-core:4.29.2`, not declared
  explicitly. DocsRoutes relies on this for YAML→JSON conversion. If liquibase-core
  is ever removed, snakeyaml must be added explicitly.

## Test file conventions
- All test files annotated `@RunWith(classOf[JUnitRunner])` to make them
  discoverable by Gradle's test runner
- Integration tests use `@Ignore` by default (require Docker); removed locally
  for a single run, then re-applied before commit
- `DocsRoutesSpec` uses `AnyWordSpec + Matchers + ScalatestRouteTest` — same
  pattern as `HabitRoutesSpec`
