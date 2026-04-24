---
name: Package layout convention in backend/
description: All backend Scala sources live under com.habittracker.*; phase-brief relative paths like src/main/scala/model/ map to com.habittracker.model under backend/.
type: project
---

Every main-source Scala file in `backend/src/main/scala/` lives under the
`com.habittracker.*` package root. Existing sub-packages: `domain`,
`repository`, `service`, `http`, `http.dto`, `config`. Phase 1 (PBI-013)
adds `model`, `client`, `prompt`.

**Why:** phase/PBI briefs sometimes reference paths like
`src/main/scala/model/Analytics.scala` which, taken literally, would create
a top-level `model` package that sits beside `com.habittracker`. The
engineer expects the project's package-root discipline to be preserved —
any new brief-specified path is rooted under `com.habittracker.*` unless
the ADR explicitly says otherwise.

**How to apply:** when a brief says "src/main/scala/<dir>/<File>.scala",
resolve it as "backend/src/main/scala/com/habittracker/<dir>/<File>.scala"
in package `com.habittracker.<dir>`. Document the mapping in the ADR so
the Developer agent does not have to re-derive it.
