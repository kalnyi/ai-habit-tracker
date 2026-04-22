---
name: Plan directory conventions in this project
description: This project stores technical plans in two parallel directories — docs/plans/ (single-PBI plans, PLAN-NNN naming) and docs/technical-plans/ (multi-PBI or batch plans).
type: project
---

The project has two parallel plan directories:

- `docs/plans/` — holds `PLAN-{NNN}-{slug}.md` files, one per PBI (see PLAN-007).
- `docs/technical-plans/` — holds multi-PBI batch plans, named by PBI range (e.g. `PBI-008-011-habit-completions.md`). Also hosts the earliest `technical-plan-habit-crud.md`.

**Why:** the architect-agent prompt hard-codes `docs/plans/PLAN-{NNN}-{short-slug}.md`, but the engineer has overridden that path for multi-PBI batch plans. Treat the user's explicit directive as authoritative over the agent prompt.

**How to apply:** for a single-PBI plan, follow the prompt's `docs/plans/PLAN-NNN-slug.md` convention. For multi-PBI batch plans, use `docs/technical-plans/PBI-{start}-{end}-{slug}.md` as directed by the engineer, not a made-up path. If the engineer gives an explicit target path for any plan, always prefer that over the prompt default.
