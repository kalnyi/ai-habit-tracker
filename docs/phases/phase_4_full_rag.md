# PHASE 4 — FULL RAG PIPELINE
# Habit Tracker · Agent Swarm Brief

## EXECUTION CONTEXT
This file is the single source of truth for Phase 4.
Agents must read this file before taking any action.
All agents in this phase receive the HARD LIMITS section as a system prompt constraint.
Do not start Phase 4 until docs/phases/phase_3_review.md confirms all Done When items pass.
Read all prior ADRs before starting:
  docs/adrs/ADR-001-phase1-pattern-detection.md
  docs/adrs/ADR-002-phase2-habit-analysis.md
  docs/adrs/ADR-003-phase3-basic-rag.md

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

## INHERITED FROM PHASES 1–3 (do not modify these)

```
src/main/scala/model/Analytics.scala          — all case classes
src/main/scala/client/AnthropicClient.scala   — unchanged
src/main/scala/client/EmbeddingClient.scala   — unchanged
src/main/scala/prompt/InsightPrompt.scala     — unchanged
src/main/scala/prompt/PromptBuilder.scala     — extended in this phase
src/main/scala/repository/TipRepository.scala — unchanged
src/main/resources/habit_tips.txt             — unchanged
GET /users/{userId}/habits/insights                 — unchanged, tests must still pass
GET /users/{userId}/habits/analysis                — unchanged, tests must still pass
```

GET /users/{userId}/habits/tips is modified in this phase (parallel retrieval).
Its existing tests must be updated to reflect the new behaviour.

---

## GOAL

Complete the RAG pipeline by adding a second retrieval source: user-written
habit notes. The system now retrieves from both the curated tips corpus
(Phase 3) and the user's own historical notes in parallel using Cats IO.

Introduce deduplication across sources, a basic RAG evaluation endpoint,
and structured logging. Engineers see a production-shaped RAG pipeline:
multi-source retrieval, result merging, quality measurement, observability.

---

## DELIVERABLES

```
POST /users/{userId}/habits/notes
→ 201 Created
→ body: UserNote

GET /users/{userId}/habits/tips         (modified from Phase 3)
→ 200 OK
→ body: TipsResponse              (extended — two source lists)

POST /users/{userId}/habits/tips/evaluate
→ 200 OK
→ body: EvalResponse
```

---

## SCOPE

### 1. New database table
Add to Docker Compose init SQL:

```sql
CREATE TABLE IF NOT EXISTS user_notes (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT NOT NULL,
  content    TEXT NOT NULL,
  embedding  vector(1536) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS user_notes_user_id_idx ON user_notes(user_id);
```

### 2. New and updated case classes
Add to src/main/scala/model/Analytics.scala:

```scala
case class UserNote(
  id:        Long,
  userId:    Long,
  content:   String,
  createdAt: java.time.Instant
)

case class NoteRequest(content: String)

case class EvalRequest(
  question:         String,
  expectedKeywords: List[String]
)

case class EvalResponse(
  passed:        Boolean,
  foundKeywords: List[String],
  narrative:     String
)
```

Update TipsResponse (breaking change — update existing endpoint and its tests):

```scala
case class TipsResponse(
  externalTips:  List[RetrievedTip],  // from habit_tips (Phase 3 source)
  personalNotes: List[RetrievedTip],  // from user_notes (Phase 4 source)
  narrative:     String
)
```

### 3. New file: src/main/scala/repository/NoteRepository.scala

Follow the exact same pattern as TipRepository — named SQL val, same method
signatures adjusted for userId parameter.

```scala
class NoteRepository[F[_]: Async](xa: Transactor[F]) {

  def insert(
    userId:    Long,
    content:   String,
    embedding: Vector[Float]
  ): F[UserNote]

  // Named val — same pattern as TipRepository.similaritySearchSql
  val similaritySearchSql: Fragment = ...

  def findSimilar(
    userId:         Long,
    queryEmbedding: Vector[Float],
    topK:           Int
  ): F[List[RetrievedTip]]
}
```

### 4. New endpoint: POST /users/{userId}/habits/notes

```scala
case req @ POST -> Root / "users" / LongVar(userId) / "habits" / "notes" =>
  req.as[NoteRequest].flatMap { body =>
    for {
      embedding <- EmbeddingClient.embed(body.content)
      note      <- noteRepo.insert(userId, body.content, embedding)
      result    <- Created(note)
    } yield result
  }
```

### 5. Update GET /users/{userId}/habits/tips — parallel retrieval

Replace the Phase 3 sequential retrieval with parallel using Cats IO.
The parallel retrieval must be a named val or def, not anonymous.

```scala
// INLINE COMMENT REQUIRED HERE:
// Explain why parallel retrieval is used:
// "Both retrievals are independent IO operations with no shared state.
// Running them in parallel with parTupled halves the retrieval latency
// compared to sequential. Cats IO parTupled runs both IOs concurrently
// on the same thread pool without blocking."

val retrieveBoth: F[(List[RetrievedTip], List[RetrievedTip])] =
  (
    tipRepo.findSimilar(queryEmbedding,  topK = TIPS_TOP_K),
    noteRepo.findSimilar(userId, queryEmbedding, topK = NOTES_TOP_K)
  ).parTupled
```

Named constants (replace Phase 3 TOP_K):
```scala
val TIPS_TOP_K:  Int = 2
val NOTES_TOP_K: Int = 2
```

Full updated endpoint flow:
```scala
case GET -> Root / "users" / LongVar(userId) / "habits" / "tips" =>
  for {
    ctx                    <- service.buildHabitContext(userId)
    query                  =  service.buildTipsQuery(ctx)
    queryEmbedding         <- EmbeddingClient.embed(query)
    (rawTips, rawNotes)    <- retrieveBoth  // parallel
    (tips, notes)          =  deduplicate(rawTips, rawNotes)
    _                      <- logRetrieval(userId, tips, notes)
    prompt                 =  PromptBuilder.build(ctx, tips, notes)
    narrative              <- AnthropicClient.complete(
                                PromptBuilder.HABIT_COACH_SYSTEM_PROMPT, prompt)
    response               =  TipsResponse(
                                externalTips  = tips,
                                personalNotes = notes,
                                narrative     = narrative)
    result                 <- Ok(response)
  } yield result
```

### 6. New pure function: deduplicate

Add to a new file: src/main/scala/service/Deduplication.scala
Must be a pure function — no F[_], no IO.

```scala
object Deduplication {

  /**
   * Removes near-duplicate results across two retrieval sources.
   * A tip and a note are considered duplicates if:
   *   - their similarity scores differ by less than 0.05, AND
   *   - their word overlap ratio exceeds 0.8
   *     (overlap = sharedWords / max(wordsA, wordsB))
   * When a duplicate pair is found, keep the item with the higher score.
   * Returns (deduplicated tips, deduplicated notes).
   */
  def deduplicate(
    tips:  List[RetrievedTip],
    notes: List[RetrievedTip]
  ): (List[RetrievedTip], List[RetrievedTip])

  // package-private for testing
  private[service] def wordOverlapRatio(a: String, b: String): Double
  private[service] def isDuplicate(a: RetrievedTip, b: RetrievedTip): Boolean
}
```

### 7. Extend PromptBuilder.scala

Add to PromptBuilder (do not modify existing methods):

```scala
def personalNotesSection(notes: List[RetrievedTip]): String
// Labelled differently from retrievedContextSection
// Uses label "YOUR PAST NOTES:" vs "RELEVANT TIPS:"
// Returns empty string if notes is Nil — no error

// Update build signature (backward compatible):
def build(
  ctx:   HabitContext,
  tips:  List[RetrievedTip] = Nil,
  notes: List[RetrievedTip] = Nil
): String =
  List(
    streakSection(ctx),
    dayPatternSection(ctx),
    rankingSection(ctx),
    timeOfDaySection(ctx),
    correlationSection(ctx),
    momentumSection(ctx),
    retrievedContextSection(tips),
    personalNotesSection(notes)
  ).filter(_.nonEmpty).mkString("\n\n")
```

### 8. Logging

Add to a new file: src/main/scala/observability/RagLogger.scala

```scala
object RagLogger {

  // Log retrieval metadata — NEVER log tip content or note content
  def logRetrieval[F[_]: Async](
    userId:         Long,
    tips:           List[RetrievedTip],
    notes:          List[RetrievedTip]
  ): F[Unit] = {
    // Log format (stdout only):
    // RAG userId=X externalCount=N personalCount=M
    //     topExternalScore=0.87 topPersonalScore=0.91
    val topExternal = tips.headOption.map(_.similarityScore).getOrElse(0.0)
    val topPersonal = notes.headOption.map(_.similarityScore).getOrElse(0.0)
    // use Async[F].delay to wrap println
  }
}
```

### 9. New endpoint: POST /users/{userId}/habits/tips/evaluate

```scala
case req @ POST -> Root / "users" / LongVar(userId) / "habits" / "tips" / "evaluate" =>
  req.as[EvalRequest].flatMap { evalReq =>
    for {
      // Run full RAG pipeline identically to GET /tips
      ctx            <- service.buildHabitContext(userId)
      query          =  service.buildTipsQuery(ctx)
      queryEmbedding <- EmbeddingClient.embed(query)
      (rawTips, rawNotes) <- retrieveBoth(queryEmbedding, userId)
      (tips, notes)  =  deduplicate(rawTips, rawNotes)
      prompt         =  PromptBuilder.build(ctx, tips, notes)
      narrative      <- AnthropicClient.complete(
                          PromptBuilder.HABIT_COACH_SYSTEM_PROMPT, prompt)
      // Evaluate: check which expected keywords appear in narrative
      narrativeLower =  narrative.toLowerCase
      found          =  evalReq.expectedKeywords.filter(k =>
                          narrativeLower.contains(k.toLowerCase))
      response       =  EvalResponse(
                          passed        = found.length == evalReq.expectedKeywords.length,
                          foundKeywords = found,
                          narrative     = narrative)
      result         <- Ok(response)
    } yield result
  }
```

### 10. Wire NoteRepository into AppResources

Update AppResources.make to include NoteRepository in the Resource
for-comprehension, following the same pattern as existing repositories.

### 11. Tests required

**NoteRepository tests** (TestContainers):
- insert: stores note with embedding, returns UserNote with generated id
- findSimilar: returns topK results ordered by similarity score descending
- findSimilar: filters by userId — does not return other users' notes
- findSimilar: empty table returns empty list without error

**Deduplication tests** (pure unit tests):
- deduplicate: identical content in both lists → only one retained
- deduplicate: no overlap between lists → both lists returned unchanged
- deduplicate: higher-scored duplicate is retained, lower-scored removed
- wordOverlapRatio: known inputs produce expected ratio
- isDuplicate: pair within thresholds returns true
- isDuplicate: pair outside thresholds returns false

**PromptBuilder tests** (pure unit tests):
- personalNotesSection: non-empty notes returns string with "YOUR PAST NOTES:" label
- personalNotesSection: empty list returns empty string
- build with both tips and notes: output contains both section labels
- build with empty notes only: output matches Phase 3 build output (regression)

**Parallel retrieval test** (IO unit test with mocks):
- Both TipRepository.findSimilar and NoteRepository.findSimilar are called
- Test verifies both mock calls are made when retrieveBoth executes

**Integration tests**:
- POST /notes then GET /tips: note content appears in personalNotes
- POST /tips/evaluate with matching keywords: passed = true
- POST /tips/evaluate with non-matching keywords: passed = false

---

## HARD LIMITS
```
NO LangChain4j or any RAG framework
NO re-ranking models or external reranking APIs
NO streaming responses
Parallel retrieval must use Cats IO parTupled — not Future, not Thread, not ZIO
Logging must never include tip content or note content — scores and counts only
deduplicate must be a pure function — no F[_], no IO
NoteRepository must follow identical pattern to TipRepository — same named SQL val
PromptBuilder.build signature change must use default args for backward compatibility
TipsResponse field rename is a breaking change — update all existing tests that use it
TIPS_TOP_K and NOTES_TOP_K must be named constants — not magic numbers
All Phase 1, 2, and 3 tests must still pass (except TipsResponse tests — update those)
Phase 1 and Phase 2 endpoints must be unmodified
```

---

## DONE WHEN (Reviewer checklist)

Run each item as an independent verification. Report pass/fail per item.
Do not mark phase complete until all items pass.

- [ ] user_notes table exists with vector(1536) column and user_id index
- [ ] POST /users/{userId}/habits/notes returns HTTP 201 with UserNote body
- [ ] Stored note has a non-null embedding in the database
- [ ] GET /users/{userId}/habits/tips returns HTTP 200 with TipsResponse
- [ ] TipsResponse has externalTips and personalNotes fields (not single tips field)
- [ ] After POST /notes, GET /tips returns that note in personalNotes
- [ ] Parallel retrieval uses parTupled — visible in code
- [ ] INLINE COMMENT on parallel retrieval explains why parallel is used
- [ ] deduplicate is a pure function in Deduplication.scala
- [ ] deduplicate unit tests pass (all 6)
- [ ] TIPS_TOP_K and NOTES_TOP_K are named constants
- [ ] PromptBuilder.personalNotesSection uses "YOUR PAST NOTES:" label
- [ ] PromptBuilder.build accepts notes with default Nil
- [ ] RagLogger logs scores and counts only — no content logged
- [ ] POST /tips/evaluate returns EvalResponse with passed/foundKeywords/narrative
- [ ] Evaluate with matching keywords: passed = true
- [ ] NoteRepository has named similaritySearchSql val
- [ ] NoteRepository filters by userId — verified by test
- [ ] NoteRepository tests pass (all 4)
- [ ] Parallel retrieval test passes
- [ ] Integration tests pass (all 3)
- [ ] AppResources includes NoteRepository
- [ ] Phase 1 tests pass unchanged
- [ ] Phase 2 tests pass unchanged
- [ ] Phase 3 tests pass (TipsResponse tests updated for new field names)
- [ ] sbt test passes in full with zero failures

---

## AGENT INSTRUCTIONS BY ROLE

### BA agent
Read Goal, Deliverables, and Scope sections.
Also read docs/phases/phase_3_pbi.md to understand accumulated context.
Produce a PBI with acceptance criteria mapped 1:1 to the Done When checklist.
Flag the TipsResponse breaking change explicitly in the PBI.
Write output to: docs/phases/phase_4_pbi.md

### Architect agent
Read the PBI, this full file, and all prior ADRs.
The HARD LIMITS block is part of your constraints.
Produce an ADR covering: NoteRepository design, parallel retrieval approach,
deduplication algorithm justification, eval endpoint design, logging strategy,
TipsResponse breaking change migration approach.
Write output to: docs/adrs/ADR-004-phase4-full-rag.md

### Developer agent
Read the PBI, the ADR, the Scope section of this file, and all prior ADRs.
The HARD LIMITS block is part of your constraints.
Start with Step 1 (database) and verify before writing application code.
Handle the TipsResponse breaking change first — update model and existing tests
before adding new functionality.
Run sbt compile after each new file. Run sbt test when all files are complete.
Fix all failures before reporting done.

### Reviewer agent
Read the Done When checklist, ADR-004, and all prior ADRs.
Verify each checklist item independently.
Pay specific attention to:
  - parTupled usage (not Future or Thread)
  - Logging content (must be scores/counts only — read the actual log statements)
  - Deduplication purity (no IO in Deduplication.scala)
  - NoteRepository userId filtering (run the specific test)
Run: sbt test and report pass/fail counts.
Write review output to: docs/phases/phase_4_review.md
Report any use of Future or non-pure deduplicate as blocking issues.

---

## POST-PHASE 4 NOTES (out of scope, document for future reference)

After Phase 4 completes, add a file docs/future_improvements.md noting:

```
CHUNKING
Notes longer than ~500 tokens should be split before embedding.
Current implementation embeds the full note as a single vector.
Long notes produce averaged embeddings that lose specific detail.

EMBEDDING CACHE
Re-embedding identical content wastes API calls and money.
A simple content-hash lookup in a cache table would prevent duplicate embeddings.

TOKEN BUDGET
No enforcement of maximum token count on retrieved context before prompt assembly.
Long retrieved content can push the prompt over the model's context window.
A token counter on the assembled prompt with truncation logic is needed.

EVAL PERSISTENCE
Eval results are returned but not stored.
Persisting (userId, question, foundKeywords, narrative, timestamp) enables
offline analysis of RAG quality over time.

FRAMEWORK INTRODUCTION POINT
Phase 4 completes a hand-rolled RAG pipeline.
This is now an appropriate point to introduce LangChain4j as a comparison:
build the same pipeline using the framework and compare code volume,
abstraction quality, and what the framework hides vs reveals.
```
