---
name: ADR trigger pattern — library choices for the HTTP layer
description: When to flag an ADR requirement in a PBI involving new libraries on the Akka HTTP stack
type: feedback
---

Any PBI that requires introducing a new library into the Akka HTTP / Scala 2.13 stack should flag an ADR requirement explicitly in the Notes section. Akka HTTP 10.5.3 uses the BSL licence and has specific Scala 2.13 compatibility constraints; library choices are not trivial.

**Why:** ADR-001 was already an engineer override of the BA/Architect recommendation. The engineer expects architectural decisions to be surfaced formally rather than assumed.

**How to apply:** If a PBI's implementation could go multiple ways at the library or infrastructure level (e.g. code-first vs spec-first OpenAPI, annotation-driven vs manual), mark it "ADR required" in Notes before handing off to the Architect.
