---
name: Project: habit CRUD PBIs (PBI-001 through PBI-006)
description: Decisions and open questions recorded during authoring of the initial habit CRUD PBIs
type: project
---

Six PBIs created on 2026-04-16 covering the Habit entity CRUD feature.

**Why:** First feature set for the Habit Tracker app (Layer 2). Backend REST only,
single user, no auth, soft delete only, Scala 2.13 + PostgreSQL.

**Decisions recorded in PBIs:**
- `frequency` stored as VARCHAR (not PostgreSQL ENUM) to allow custom frequencies later
  without schema migrations — must be confirmed in schema ADR.
- `id` is UUID generated at application layer.
- `deleted_at IS NULL` is the active-habit filter; soft-deleted habits return 404.
- DELETE on an already-deleted habit returns 404 (not idempotent 204) — engineer may
  override this preference.
- List endpoint returns a bare JSON array for now; Architect should consider a `data`
  envelope for future pagination compatibility. Engineer preference not yet confirmed.

**Open questions / pending engineer decisions (as of 2026-04-16):**
- Response envelope for GET /habits: bare array vs `{ "data": [] }` wrapper.
- Idempotency of DELETE: 404 vs 204 on already-deleted resource.
- Framework ADR (http4s vs Play) — unresolved, blocks all implementation.
- Schema ADR (frequency column strategy, updated_at trigger vs app-layer) — unresolved,
  blocks PBI-001 implementation.

**How to apply:** When writing future PBIs that touch the Habit entity, reference these
decisions to stay consistent. When the open questions are resolved, update this memory.
