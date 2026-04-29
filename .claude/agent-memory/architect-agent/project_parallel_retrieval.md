---
name: Parallel multi-source retrieval pattern
description: Cross-phase rule — RAG fan-out IOs use cats parTupled; never Future, Thread, or ZIO. Sequential .flatMap is forbidden for the parallel slot.
type: project
---

For any RAG pipeline that fans out across more than one retrieval source,
the pattern locked in ADR-011 §3 is:

```scala
import cats.syntax.parallel._

private def retrieveBoth(
    queryEmbedding: Vector[Float],
    userId:         Long
): IO[(List[RetrievedTip], List[RetrievedTip])] =
  (
    tipRepo.findSimilar(queryEmbedding, TIPS_TOP_K),
    noteRepo.findSimilar(userId, queryEmbedding, NOTES_TOP_K)
  ).parTupled
```

**Why:**
- The HARD LIMITS in every RAG-related phase brief forbid `Future`,
  `Thread`, ZIO. Only `parTupled` from `cats.syntax.parallel._` is
  permitted.
- ADR-009 §5 already proved the analytics endpoint can run eight
  concurrent Doobie queries on the existing HikariCP pool. Adding two
  more from the tips path is below pool capacity.
- The retrieval helper must be a parameterised `def` taking
  `(queryEmbedding, userId)`, not a class-level `val`. A `val` would
  either capture wrong values or require thread-local state.

**How to apply:**
- When planning any new endpoint that fetches from N independent
  retrieval sources, plan the fan-out as a single `parTupled` call,
  named with a `def` that takes the per-request inputs as parameters.
- If only one source is involved, this rule does not fire — sequential
  `.flatMap` is the right shape for the single-source case.
- If three or more sources appear, the same `parTupled` idiom scales
  to a 3- or 4-tuple. The Cats Parallel instance for IO supports up to
  Tuple22 in principle.
- When wiring the route's for-comprehension, do NOT use a `.flatMap`
  chain between any two retrieval IOs — even one would violate AC-7
  in the Phase 4 PBI and the same rule will apply to future RAG PBIs.
- Architect Memory file: `.claude/agent-memory/architect-agent/MEMORY.md`
  references this file as the canonical pattern source.
