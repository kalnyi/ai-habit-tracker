---
name: Feedback: PBI decomposition for CRUD features
description: Rules for how to split habit CRUD operations into separate PBIs and when to create a foundation PBI
type: feedback
---

Decompose CRUD operations into one PBI per operation (Create, List, Get, Update, Delete),
plus a separate foundation PBI for the domain model and DB schema when those do not yet
exist.

**Why:** The schema and domain model are a shared dependency for all CRUD endpoints. If
bundled into the Create PBI it becomes hard to implement or review List/Get/Update/Delete
independently. A separate foundation PBI (PBI-001 pattern) makes the dependency explicit
and allows the Architect to tackle schema ADR decisions before any endpoint PBI begins.

**How to apply:** Any time a feature introduces a new database entity, produce a
foundation PBI (domain model + schema + migration) first, then separate PBIs for each
operation. Flag the foundation PBI as an ADR prerequisite blocker.
