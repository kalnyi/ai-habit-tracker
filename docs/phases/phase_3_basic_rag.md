# PHASE 3 — BASIC RAG (PERSONALISED TIPS)
# Habit Tracker · Agent Swarm Brief

## EXECUTION CONTEXT
This file is the single source of truth for Phase 3.
Agents must read this file before taking any action.
All agents in this phase receive the HARD LIMITS section as a system prompt constraint.
Do not start Phase 3 until docs/phases/phase_2_review.md confirms all Done When items pass.
Read docs/adrs/ADR-001-phase1-pattern-detection.md and
docs/adrs/ADR-002-phase2-habit-analysis.md before starting.

## STACK (read-only reference, do not change)
- HTTP:        http4s + Ember server
- Effects:     Cats Effect 3 — all async is F[_]: Async or IO, no Future
- Database:    Doobie + Postgres (Docker Compose) + pgvector extension
- JSON:        Circe via http4s-circe, io.circe.generic.auto._
- HTTP client: sttp with cats-effect backend
- Testing:     munit-cats-effect, CatsEffectSuite, TestContainers
- Build:       sbt
- LLM model:   claude-sonnet-4-20250514
- Embeddings:  text-embedding-3-small via OpenAI API (dimension: 1536)

---

## INHERITED FROM PHASES 1 AND 2 (do not modify these)

```
src/main/scala/model/Analytics.scala         — HabitContext, InsightResponse, AnalysisResponse
src/main/scala/client/AnthropicClient.scala  — direct sttp Anthropic call
src/main/scala/prompt/InsightPrompt.scala    — Phase 1 prompt (unchanged)
src/main/scala/prompt/PromptBuilder.scala    — Phase 2 prompt builder (extended here)
GET /users/{userId}/habits/insights                — unchanged, tests must still pass
GET /users/{userId}/habits/analysis               — unchanged, tests must still pass
```

Any modification to the above files beyond what is explicitly described in
this file's Scope section is a HARD LIMIT violation.

---

## GOAL

Introduce RAG from first principles — no framework. Embed a static corpus of
habit science tips using the OpenAI embeddings API. Store embeddings in
pgvector. At request time, embed a query derived from the user's habit context,
retrieve the top-K most semantically similar tips, and inject them into the
prompt as grounding context.

Every step of the pipeline must be explicit Scala code with inline comments
explaining what is happening. This is a learning objective — engineers must be
able to read the code and understand embeddings, similarity search, and
retrieval-augmented generation without prior knowledge.

---

## DELIVERABLE

```
GET /users/{userId}/habits/tips
→ 200 OK
→ body: TipsResponse
```

```scala
case class TipsResponse(
  tips:      List[RetrievedTip],
  narrative: String
)
```

---

## SCOPE

### 1. Infrastructure — enable pgvector

Add to Docker Compose Postgres init script (create if not exists:
docker/init.sql or similar):

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS habit_tips (
  id        BIGSERIAL PRIMARY KEY,
  content   TEXT NOT NULL,
  embedding vector(1536) NOT NULL
);
```

Verify Docker Compose restarts cleanly with the extension enabled before
proceeding.

### 2. New case classes
Add to src/main/scala/model/Analytics.scala:

```scala
case class HabitTip(id: Long, content: String)

case class RetrievedTip(
  tip:             HabitTip,
  similarityScore: Double    // cosine similarity, range 0.0–1.0
)

case class TipsResponse(
  tips:      List[RetrievedTip],
  narrative: String
)
```

Also extend HabitContext with one optional field (default Nil, backward compatible):

```scala
case class HabitContext(
  // all existing Phase 1 and Phase 2 fields unchanged...
  retrievedTips: List[String] = Nil   // populated only in /tips endpoint flow
)
```

### 3. New file: src/main/scala/client/EmbeddingClient.scala

Direct sttp call to OpenAI embeddings API. Follow the exact same pattern as
AnthropicClient — no abstraction, no trait, call visible at call site.

```scala
object EmbeddingClient {

  // INLINE COMMENT REQUIRED HERE:
  // Explain what an embedding vector is in plain terms.
  // Example: "An embedding is a list of 1536 numbers that represent the
  // semantic meaning of a text. Texts with similar meaning produce vectors
  // that are close together in 1536-dimensional space."

  val MODEL:     String = "text-embedding-3-small"
  val API_URL:   String = "https://api.openai.com/v1/embeddings"
  val DIMENSION: Int    = 1536

  def embed[F[_]: Async](text: String): F[Vector[Float]] = {
    // sttp POST to API_URL
    // read OPENAI_API_KEY from environment — raise error at startup if missing
    // parse response: data[0].embedding -> Vector[Float]
  }
}
```

### 4. New file: src/main/scala/repository/TipRepository.scala

```scala
class TipRepository[F[_]: Async](xa: Transactor[F]) {

  def insert(content: String, embedding: Vector[Float]): F[HabitTip]

  // INLINE COMMENT REQUIRED HERE on similaritySearchSql:
  // Explain cosine similarity in plain terms.
  // Example: "Cosine similarity measures the angle between two vectors.
  // Score of 1.0 means identical meaning, 0.0 means unrelated.
  // The <=> operator is pgvector's cosine distance (1 - similarity),
  // so we subtract from 1 to get the similarity score."

  val similaritySearchSql: Fragment =
    fr"""SELECT id, content,
                1.0 - (embedding <=> ${/* queryEmbedding placeholder */}::vector) AS score
         FROM habit_tips
         ORDER BY embedding <=> ${/* queryEmbedding placeholder */}::vector
         LIMIT ${/* topK placeholder */}"""

  // INLINE COMMENT REQUIRED HERE on findSimilar:
  // Explain why ORDER BY distance gives semantic similarity results.

  def findSimilar(
    queryEmbedding: Vector[Float],
    topK:           Int
  ): F[List[RetrievedTip]]
}
```

Note: Implement similaritySearchSql as a proper parameterised Doobie query.
The fragment above shows intent — write it correctly for Doobie.
The val must be named (not an anonymous string literal).

### 5. New file: src/main/resources/habit_tips.txt

One tip per line. Minimum 25 tips. Cover: habit formation, maintenance,
recovery after missing, environmental design, motivation, tracking.

Required content variety — include tips from each of these areas:
- Implementation intentions (when/where/how planning)
- Habit stacking (pairing new habits with existing ones)
- Environmental design (reducing friction)
- Recovery (what to do after missing a day)
- Tracking and measurement
- Intrinsic motivation
- Social accountability
- The role of identity in habit formation
- Sleep and energy as habit foundations
- Habit breaking (for negative habits)

### 6. New file: src/main/scala/scripts/SeedTips.scala

One-time script to embed and store the tip corpus. Not part of the server.
Run manually: sbt "runMain scripts.SeedTips"

```scala
object SeedTips extends IOApp.Simple {

  def run: IO[Unit] =
    // 1. Read lines from resources/habit_tips.txt
    // 2. For each tip, check if content already exists in habit_tips table
    // 3. If not exists: call EmbeddingClient.embed, then TipRepository.insert
    // 4. Log progress: "Seeded tip N/total: [first 50 chars]"
    // 5. Log skipped: "Skipped (already exists): [first 50 chars]"
    // Must be idempotent — running twice produces no duplicates
}
```

### 7. Extend PromptBuilder.scala

Add to PromptBuilder (do not modify existing methods):

```scala
// INLINE COMMENT REQUIRED HERE:
// Explain what "grounding" means in RAG:
// "Retrieved tips are injected here to ground the LLM's response in
// external knowledge. Without grounding, the model generates from its
// training data alone. With grounding, it synthesises retrieved facts
// with user-specific data, producing more relevant and accurate tips."

def retrievedContextSection(tips: List[RetrievedTip]): String
// Returns empty string if tips is Nil — graceful, no error

// Update build signature to accept tips (backward compatible default):
def build(ctx: HabitContext, tips: List[RetrievedTip] = Nil): String =
  List(
    streakSection(ctx),
    dayPatternSection(ctx),
    rankingSection(ctx),
    timeOfDaySection(ctx),
    correlationSection(ctx),
    momentumSection(ctx),
    retrievedContextSection(tips)  // empty string filtered out if Nil
  ).filter(_.nonEmpty).mkString("\n\n")
```

### 8. New service method and endpoint

Service method (add to HabitService or AnalyticsService):

```scala
def buildTipsQuery(ctx: HabitContext): String
// Derives a plain-text query from HabitContext for embedding.
// Example: combine top habit names + worst day patterns into a sentence.
// Must be pure — no F[_], no IO.
```

Endpoint:

```scala
case GET -> Root / "users" / LongVar(userId) / "habits" / "tips" =>
  for {
    ctx           <- service.buildHabitContext(userId)
    query         =  service.buildTipsQuery(ctx)
    queryEmbedding <- EmbeddingClient.embed(query)
    retrieved     <- tipRepo.findSimilar(queryEmbedding, topK = TOP_K)
    prompt        =  PromptBuilder.build(ctx, retrieved)
    narrative     <- AnthropicClient.complete(PromptBuilder.HABIT_COACH_SYSTEM_PROMPT, prompt)
    response      =  TipsResponse(tips = retrieved, narrative = narrative)
    result        <- Ok(response)
  } yield result
```

TOP_K must be a named constant (value: 3), not a magic number.

### 9. Tests required

**TipRepository tests** (TestContainers with pgvector-enabled Postgres):
- insert: stores tip and returns HabitTip with generated id
- findSimilar: given 10 seeded tips, returns exactly topK results
- findSimilar: results are ordered by similarity score descending
- findSimilar: empty table returns empty list without error

**PromptBuilder tests** (pure unit tests):
- retrievedContextSection: non-empty tips returns non-empty string
- retrievedContextSection: empty list returns empty string without error
- build with tips: output contains retrieved context section content
- build without tips: output equals Phase 2 build output (regression)

**SeedTips idempotency test**:
- Run seed twice against test database
- Assert row count equals tip count in habit_tips.txt (no duplicates)

---

## HARD LIMITS
```
NO LangChain4j or any RAG framework — every step must be explicit Scala code
NO dynamic corpus — tips are seeded once from habit_tips.txt, not fetched at runtime
NO re-embedding corpus tips at request time — embeddings are pre-computed and stored
NO changes to Phase 1 GET /users/{userId}/habits/insights endpoint or its tests
NO changes to Phase 2 GET /users/{userId}/habits/analysis endpoint or its tests
NO changes to AnthropicClient.scala
NO removal or renaming of existing HabitContext fields
Similarity SQL must be a named val in TipRepository — not an anonymous string
Inline comments at the 4 required locations are mandatory — reviewer will check
EmbeddingClient must follow the same direct sttp pattern as AnthropicClient
TOP_K must be a named constant — not a magic number at the call site
OPENAI_API_KEY must come from environment — never hardcoded
PromptBuilder.build signature change must use default args for backward compatibility
```

---

## DONE WHEN (Reviewer checklist)

Run each item as an independent verification. Report pass/fail per item.
Do not mark phase complete until all items pass.

- [ ] Docker Compose starts cleanly with pgvector extension enabled
- [ ] habit_tips table exists with vector(1536) column
- [ ] SeedTips runs without error
- [ ] SeedTips is idempotent — running twice produces no duplicate rows
- [ ] GET /users/{userId}/habits/tips returns HTTP 200
- [ ] Response body deserialises to TipsResponse with tips and narrative
- [ ] tips list contains exactly TOP_K (3) items
- [ ] Each RetrievedTip has a non-zero similarityScore
- [ ] narrative is a non-empty string
- [ ] EmbeddingClient contains a direct sttp call — same pattern as AnthropicClient
- [ ] OPENAI_API_KEY read from environment — not hardcoded
- [ ] TipRepository.similaritySearchSql is a named val
- [ ] Inline comment exists in EmbeddingClient (explaining embeddings)
- [ ] Inline comment exists on TipRepository.similaritySearchSql (explaining cosine similarity)
- [ ] Inline comment exists on TipRepository.findSimilar (explaining ORDER BY distance)
- [ ] Inline comment exists in PromptBuilder.retrievedContextSection (explaining grounding)
- [ ] TOP_K is a named constant
- [ ] PromptBuilder.build accepts tips with default Nil (backward compatible)
- [ ] All TipRepository tests pass
- [ ] All new PromptBuilder tests pass
- [ ] SeedTips idempotency test passes
- [ ] Phase 1 tests still pass unchanged
- [ ] Phase 2 tests still pass unchanged
- [ ] sbt test passes in full with zero failures

---

## AGENT INSTRUCTIONS BY ROLE

### BA agent
Read Goal, Deliverable, and Scope sections.
Also read docs/phases/phase_2_pbi.md to understand accumulated context.
Produce a PBI with acceptance criteria mapped 1:1 to the Done When checklist.
Write output to: docs/phases/phase_3_pbi.md

### Architect agent
Read the PBI, this full file, ADR-001, and ADR-002.
The HARD LIMITS block is part of your constraints.
Produce an ADR covering: pgvector setup decisions, EmbeddingClient design,
TipRepository query approach, how SeedTips integrates with the build,
how the tips endpoint composes existing and new components.
Write output to: docs/adrs/ADR-003-phase3-basic-rag.md

### Developer agent
Read the PBI, the ADR, the Scope section of this file, and prior ADRs.
The HARD LIMITS block is part of your constraints.
Start with infrastructure (Step 1) and verify Docker Compose before writing code.
Implement all items in Scope in order.
Run sbt compile after each new file.
Run SeedTips script and verify rows inserted before implementing the endpoint.
Run sbt test when all files are complete.
Fix all failures before reporting done.

### Reviewer agent
Read the Done When checklist, ADR-003, and prior ADRs.
Verify each checklist item independently.
Specifically check all 4 inline comment locations — read the actual comments
and verify they explain the concept in plain terms, not just restate the code.
Run: sbt test and report pass/fail counts.
Write review output to: docs/phases/phase_3_review.md
Report missing or inadequate inline comments as blocking issues.

---

## CARRY-OVER TO PHASE 4

Phase 4 reads this section to understand what it inherits. Do not modify these
contracts during Phase 3 — if something was built differently, update the Phase 4
file before starting Phase 4.

```
FILE: src/main/scala/repository/TipRepository.scala
  Phase 4 creates NoteRepository following the exact same pattern
  TipRepository itself is unchanged

FILE: src/main/scala/client/EmbeddingClient.scala
  Phase 4 reuses without modification
  Used for both corpus embedding (Phase 3) and user note embedding (Phase 4)

FILE: src/main/scala/prompt/PromptBuilder.scala
  Phase 4 ADDS personalNotesSection(notes: List[RetrievedTip]): String
  Phase 4 UPDATES build signature:
    build(ctx, tips: List[RetrievedTip] = Nil, notes: List[RetrievedTip] = Nil)
  Existing methods unchanged

ENDPOINT: GET /users/{userId}/habits/tips
  Phase 4 REPLACES sequential retrieval with parallel retrieval (Cats IO parTupled)
  Phase 4 adds deduplication step after retrieval
  Endpoint route and response type change — TipsResponse is extended

CONSTANT: TOP_K
  Phase 4 uses TOP_K = 2 per source (tips: 2, notes: 2) — update constant or
  create two named constants: TIPS_TOP_K and NOTES_TOP_K

DATABASE:
  habit_tips table — unchanged
  Phase 4 adds user_notes table (same pgvector pattern)
```
