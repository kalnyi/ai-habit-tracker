---
name: RagLogger privacy contract
description: Cross-phase rule — RAG retrieval logs must record scores and counts only, never tip or note content. Personal notes may contain PII.
type: project
---

ADR-011 §5 locks the privacy contract for retrieval-quality logging:

`RagLogger.logRetrieval[F[_]: Async](userId, tips, notes): F[Unit]` MUST
emit a single stdout line of the form:

```
RAG userId=42 externalCount=2 personalCount=2 topExternalScore=0.87 topPersonalScore=0.91
```

**It MUST NOT log the `content` field of any RetrievedTip.**

**Why:**
- Personal notes (`user_notes.content`) are user-supplied free text and
  may contain PII (medical, family, location, financial).
- The curated `habit_tips.content` is non-sensitive, but logging it
  while *not* logging notes invites reviewer drift later ("why don't
  we just log the note too?"). The cleanest contract is "log neither".
- The retrieval-quality signal we care about (empty results, low
  confidence, source imbalance) is fully expressible as count + top
  score per source. Content is not needed for observability.

**How to apply:**
- Any future logger that consumes `RetrievedTip`, `UserNote`, or
  similar embedding-derived types must follow the same contract: emit
  scores, counts, dimensions, ids if useful — never content.
- The Reviewer is instructed to read the actual log statements and
  verify by inspection. Plans that introduce new RAG logging must
  include this verification step.
- Output target is stdout via `Async[F].delay(println(...))` for the
  PoC. A future PBI may swap to log4cats or structured logging
  behind the same call site; the privacy contract holds regardless
  of implementation.
- Method signature is polymorphic in `F[_]: Async`, called as
  `.logRetrieval[IO](...)` at the route call site — same pattern as
  `EmbeddingClient.embed[IO]` and `AnthropicClient.complete[IO]`.
