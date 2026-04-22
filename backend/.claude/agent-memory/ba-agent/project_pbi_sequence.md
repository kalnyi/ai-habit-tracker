---
name: PBI numbering and current backlog state
description: Which PBIs exist and what the next available number is
type: project
---

PBIs 001-006 cover the core habit CRUD:
- PBI-001: Habit domain model and DB schema
- PBI-002: POST /habits (create)
- PBI-003: GET /habits (list)
- PBI-004: GET /habits/{id} (get single)
- PBI-005: PUT /habits/{id} (update)
- PBI-006: DELETE /habits/{id} (soft-delete)

PBI-007 has been written: OpenAPI / Swagger UI documentation endpoint.

PLAN-007 (replace Flyway with Liquibase) exists as a plan doc but did not consume the PBI-007 slot — that plan has no corresponding PBI file.

Next available PBI number: 008.

**Why:** Tracking this avoids numbering collisions across sessions.

**How to apply:** Always check docs/pbis/ for the highest existing number before assigning a new PBI number.
