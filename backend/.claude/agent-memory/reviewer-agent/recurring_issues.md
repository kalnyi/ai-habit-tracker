---
name: Recurring issues across reviews
description: Patterns worth flagging proactively in future Developer agent output
type: project
---

## Observed in PBI-007 (first review session — 2026-04-17)

### Java number types → Circe precision loss
When converting SnakeYAML output (java.lang.Number) to Circe JSON, developers
reach for `n.doubleValue()` which silently converts integers to floating-point.
Flag any `snakeYamlToCirce`-style helper that uses `doubleValue()` without an
integer-first branch (`Json.fromLong` for Integer/Long types).

### lazy val vs val inconsistency on expensive initialisation
Classpath resources loaded lazily (first-request failure) while others are eager.
Suggest making all spec-file reads eager (`val` in constructor) so packaging errors
surface at startup.

### Plan-specified test assertions omitted
The plan listed specific assertions (HTTP method presence per path) that were not
implemented in the test. Future Developer output should be checked against the
plan's test plan section to confirm every listed assertion has a corresponding
`in` clause.

### Missing comment for transitive dependency assumptions
ADR-004 explicitly asked for a comment in build.gradle noting that SnakeYAML
comes from liquibase-core transitively. The Developer omitted it. Check that ADR
"consequences / notes" sections requesting build file comments are honoured.
