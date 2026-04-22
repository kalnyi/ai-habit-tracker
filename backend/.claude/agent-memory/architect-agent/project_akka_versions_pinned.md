---
name: Akka HTTP pinned to 10.5.3 / 2.8.8 due to Maven Central availability
description: Akka HTTP 10.6.x and Akka 2.9.x are published only to Lightbend's BSL private Maven repo; Central tops out at 10.5.3 / 2.8.8
type: project
---

The project's build.gradle pins `akkaHttpVersion = '10.5.3'` and
`akkaVersion = '2.8.8'`. This is not a free choice — later Akka versions
(10.6.x, 2.9.x) are published only to Lightbend's BSL-licensed private Maven
repository, not to Maven Central.

**Why:** The project consumes only Maven Central; adding a Lightbend resolver
is a licensing decision that has not been made. ADR-001 notes the BSL caveat
for any future commercialisation. The deviation from the original ADR-1
versions (which specified 10.6.x) was forced by dependency resolution and is
flagged in a comment at the top of `build.gradle`.

**How to apply:** When planning any new Akka-HTTP-adjacent dependency (e.g.
`akka-http-circe`, `akka-stream-testkit`, a Swagger/OpenAPI binding library),
check that the version being proposed is binary-compatible with Akka HTTP
10.5.x and published to Maven Central. Do not assume latest-stable versions
of Akka-ecosystem libraries are reachable; spot-check Maven Central before
including them in a plan.
