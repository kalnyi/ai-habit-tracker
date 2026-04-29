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

PBI-007: OpenAPI / Swagger UI documentation endpoint.
PBI-008 through PBI-011: Habit completion CRUD (domain/schema, record, list, delete).
PBI-012: Add user to domain.
PBI-013: Phase 1 — Pattern detection and LLM narrative endpoint (GET /users/{userId}/habits/insights).
  Output file: docs/phases/phase_1_pbi.md
PBI-014: Phase 2 — Habit analysis endpoint with extended context and structured prompt (GET /users/{userId}/habits/analysis).
  Output file: docs/phases/phase_2_pbi.md

Next available PBI number: 015.

**Why:** Tracking this avoids numbering collisions across sessions.

**How to apply:** Always check docs/pbis/ for highest numbered file AND docs/phases/ for phase PBI files
before assigning a new PBI number. Phase PBIs live in docs/phases/ not docs/pbis/.
