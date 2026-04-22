# BETWEEN-PHASE RETROSPECTIVE
# Habit Tracker · Agent Swarm

## USAGE
Run this prompt against the Architect agent after each phase completes
and before issuing the next phase brief.

Replace [N] with the completed phase number before running.

Invoke with:
  claude "Read docs/phases/retrospective.md and execute the Phase [N] retrospective."

---

## TASK

RETROSPECTIVE FOR PHASE [N] — read and report only, no code changes.

Read the following in order:
1. docs/phases/phase_[N]_review.md
2. docs/adrs/ADR-00[N]-phase[N]-*.md
3. All MEMORY files: .agent/architect/MEMORY.md, .agent/developer/MEMORY.md,
   .agent/reviewer/MEMORY.md
4. git log --oneline for commits made since the Phase [N] start tag

Produce a retrospective report with exactly these five sections.
Do not add sections. Do not omit sections. Report facts — no speculation.

---

### SECTION 1: PATTERNS ESTABLISHED

List coding and architectural patterns Phase [N+1] must follow.
Be specific: include file names, function signatures, naming conventions,
and any patterns that diverged from the phase brief.

Format each pattern as:
  PATTERN: [short name]
  LOCATION: [file path]
  RULE: [what Phase N+1 must do]

---

### SECTION 2: TECHNICAL DEBT

List shortcuts, TODOs, skipped tests, or known issues introduced in Phase [N]
that Phase [N+1] must not worsen.
Include file and approximate line reference if known.

Format each item as:
  DEBT: [description]
  LOCATION: [file:line if known]
  RISK: [what breaks if ignored]

If none: write "NONE IDENTIFIED"

---

### SECTION 3: PHASE [N+1] BRIEF AMENDMENTS

List specific additions or corrections needed in docs/phases/phase_[N+1]_*.md
based on what was actually built in Phase [N].

Common reasons for amendments:
- A case class field was renamed or retyped vs the brief
- A file was placed in a different package than specified
- A method signature differs from the brief's carry-over contract
- A test helper was created that Phase [N+1] should reuse

Format each amendment as:
  AMEND: [which section of the Phase N+1 brief]
  CHANGE: [what needs updating and why]
  ORIGINAL: [what the brief currently says]
  CORRECTED: [what it should say]

If none: write "NONE REQUIRED"

---

### SECTION 4: MEMORY UPDATES REQUIRED

List which MEMORY file sections need updating before Phase [N+1] starts.
Be specific about what the update should say — do not just flag that an
update is needed.

Format each item as:
  FILE: [.agent/X/MEMORY.md]
  SECTION: [section name or heading]
  UPDATE: [exact content to add or replace]

After producing this report, apply all MEMORY updates listed here.
Confirm each update with: "Updated [file] — [section]"

---

### SECTION 5: PHASE READINESS

State one of:
  READY — Phase [N+1] can start. All Done When items passed. No blocking debt.
  NOT READY — [list specific blocking items that must be resolved first]

If NOT READY, do not start Phase [N+1] until the blocking items are resolved
and this retrospective is re-run.

---

## OUTPUT

Write the full retrospective report to:
  docs/phases/phase_[N]_retrospective.md

Then apply MEMORY updates from Section 4.

Report completion as:
  RETROSPECTIVE COMPLETE
  Phase [N] status: READY / NOT READY
  MEMORY files updated: [list]
  Phase [N+1] brief amendments: [count] items — review before starting
