# ADR-011: Phase 4 full RAG — NoteRepository mirror of TipRepository, parallel multi-source retrieval via parTupled, pure cross-source deduplication, scores-only RAG logging, TipsResponse breaking-change rename, eval endpoint deferred

## Status
Accepted

## Context

Phase 4 (PBI-018) extends the Phase 3 single-source RAG pipeline (`habit_tips`
corpus only) into a multi-source RAG pipeline that adds the user's own
historical notes as a second retrieval surface. The result is the
production-shaped pipeline an engineer would expect to see in any RAG product:
two independent vector indexes queried in parallel, results merged and
deduplicated across sources, retrieval quality logged for observability, and
the LLM call grounded by both sources.

Eight architectural questions need an explicit, written resolution before the
Developer agent starts:

1. **`NoteRepository` shape and effect type.** The Phase 4 brief shows `F[_]:
   Async` in the repository signatures. ADR-010 §3 already corrected this
   drift for `TipRepository`, and Architect Memory locks the rule: every
   repository in this codebase is `class …(xa: Transactor[IO])` with `IO[_]`
   returns. `NoteRepository` follows the same pattern.
2. **`user_notes` table boundary.** ADR-010 §1 placed `habit_tips` (a corpus
   table) in the Docker Compose init-script directory rather than Liquibase.
   `user_notes` is owned by the application (the user inserts rows at
   request time), but it shares the pgvector wire format and is consumed by
   the same RAG pipeline. The decision needs a deliberate restatement so
   future agents do not introduce a Liquibase changeset by reflex.
3. **Parallel-retrieval mechanics.** The two retrievals (`TipRepository.findSimilar`
   and `NoteRepository.findSimilar`) are independent IO operations with no
   shared state. The Phase 4 HARD LIMIT mandates `parTupled` from
   `cats.syntax.parallel._`; `Future`, `Thread`, and `ZIO` are explicitly
   forbidden. The decision needs to record *why* `parTupled` (vs `mapN`,
   `parMapN`, sequential `flatMap`) and how it interacts with the existing
   HikariCP pool.
4. **`retrieveBoth` definition style.** AC-7 mandates a `def retrieveBoth`
   accepting `(queryEmbedding, userId)` as parameters. A `val retrieveBoth`
   that closes over request-scoped state is forbidden. The decision needs
   to record why a parameterised `def` is the right shape.
5. **Deduplication algorithm.** The PBI specifies two thresholds: similarity
   scores within 0.05 of each other and word-overlap ratio above 0.8. The
   ADR needs to justify those numbers (PoC defaults, easily tuned, no
   training data required) and the purity constraint (no `F[_]`, no `IO`).
6. **`TipsResponse` breaking change.** The Phase 3 wire shape was
   `(tips, narrative)`. Phase 4 changes it to `(externalTips,
   personalNotes, narrative)`. This is the only Phase 3 surface the PBI
   permits to break. The migration path needs to be explicit.
7. **Logging contract.** The brief mandates `RagLogger` log scores and counts
   only — never tip or note content. The decision needs to record why
   (privacy, no PII in logs) and where (stdout via `Async[F].delay(println)`).
8. **Eval endpoint removed.** The Phase 4 brief includes a third deliverable
   `POST /users/{userId}/habits/tips/evaluate`. The engineer removed this
   per decision 2026-04-29. The ADR needs to record the reason so a future
   PBI does not reintroduce it without a fresh decision.

The HARD LIMITS block forbids modification of `AnthropicClient.scala`,
`EmbeddingClient.scala`, `InsightPrompt.scala`, `InsightsRoutes.scala`,
`AnalysisRoutes.scala`, `TipRepository.scala`, the Phase 1/2 test files, and
all Phase 3 test files except for the `TipsResponse` field rename. ADR-008,
ADR-009, and ADR-010 lock conventions this ADR builds on (file layout,
no-trait clients, repository `IO` returns, semiauto codecs, route ordering,
Testcontainers `@Ignore` discipline, Gradle build, ScalaTest pattern,
hand-rolled pgvector wire format, no new dependencies).

A practical context point that shaped the parallel-retrieval decision: the
analysis endpoint already runs eight Doobie queries concurrently via
`parTupled` + `parTraverse` (ADR-009 §5) without saturating the pool. Adding
two more concurrent queries to the tips path is safe under the existing
HikariCP sizing.

---

## Decision

### 1. `NoteRepository` mirrors `TipRepository` exactly — `IO`-typed, named SQL `val`, userId filter in the SQL itself

`com.habittracker.repository.NoteRepository` is a `final class` parameterised
on `Transactor[IO]` with `IO[_]` returns throughout. No trait, no abstract
class, no DI binding. The shape is byte-for-byte parallel to `TipRepository`,
adjusted only for the `userId` parameter and the `user_notes` table:

```scala
final class NoteRepository(transactor: Transactor[IO]) {

  private def embeddingToPgLiteral(v: Vector[Float]): String =
    v.mkString("[", ",", "]")

  private def insertSql(
      userId:           Long,
      content:          String,
      embeddingLiteral: String
  ): Update0 =
    sql"""
      INSERT INTO user_notes (user_id, content, embedding)
      VALUES ($userId, $content, $embeddingLiteral::vector)
      RETURNING id, created_at
    """.update

  // Cosine similarity over the user's own notes — same operator semantics
  // as TipRepository.similaritySearchSql. The WHERE user_id = $userId
  // clause enforces the user-scoping rule from ADR-007/008: a query for
  // user A must never return notes belonging to user B.
  private def similaritySearchSql(
      userId:         Long,
      queryEmbedding: Vector[Float],
      topK:           Int
  ): Query0[(Long, String, Double)] = {
    val embeddingLiteral: String = embeddingToPgLiteral(queryEmbedding)
    sql"""
      SELECT id,
             content,
             1.0 - (embedding <=> $embeddingLiteral::vector) AS score
      FROM user_notes
      WHERE user_id = $userId
      ORDER BY embedding <=> $embeddingLiteral::vector
      LIMIT $topK
    """.query[(Long, String, Double)]
  }

  def insert(
      userId:    Long,
      content:   String,
      embedding: Vector[Float]
  ): IO[UserNote] = ...

  def findSimilar(
      userId:         Long,
      queryEmbedding: Vector[Float],
      topK:           Int
  ): IO[List[RetrievedTip]] = ...
}
```

**Why `IO` directly, not `F[_]: Async`:**

- ADR-010 §3 already settled this for `TipRepository`. Architect Memory marks
  every "repository accepts `F[_]`" wording in the brief as documentation
  drift. Diverging here would split the codebase into two repository styles.
- All three other repositories (`DoobieHabitRepository`,
  `DoobieHabitCompletionRepository`, `DoobieAnalyticsRepository`) and the
  Phase 3 `TipRepository` use `class …(xa: Transactor[IO])` with `IO[_]`
  returns. Consistency is the load-bearing reason — a reviewer reading any
  repository file should see the same shape.
- The polymorphism the brief implies (so any `F[_]: Async` could be plugged
  in) is not needed: `AppResources` constructs a single `Transactor[IO]` and
  passes it to every repository. There is no second effect type in the
  application.

**Why a named `similaritySearchSql` `def`, not an inline string:**

- AC-15 in the PBI mandates a "named `similaritySearchSql` val" with the
  same constraint as `TipRepository.similaritySearchSql`. As ADR-010 §4
  recorded, the *name* and the explanatory comment are the load-bearing
  requirements; whether the binding is `val` or `def` is incidental to the
  architectural intent. Doobie cannot bind a `Vector[Float]` to a vector
  column without a `Meta[Vector[Float]]`, so the SQL is parameterised by
  the embedding (a `String` literal at the wire level) and `def` is the
  necessary mechanic. The Reviewer must verify that the *name*
  `similaritySearchSql` appears in source, not whether the keyword is
  `val`.

**Why `userId` filtering happens in the SQL, not in Scala post-filtering:**

- A post-filter in Scala would still pull other users' rows over the wire
  before discarding them — wasteful and a security smell. The `WHERE
  user_id = $userId` clause uses the `user_notes_user_id_idx` index to
  prune the candidate set at the database level.
- Architect Memory locks this rule: "NoteRepository (Phase 4) must also
  filter by userId in findSimilar — must not return other users' notes.
  The Reviewer will specifically verify this with a dedicated test."
- The Phase 4 PBI AC-16 mandates a Testcontainers spec with two distinct
  user IDs that asserts a query for user A returns no rows belonging to
  user B. The SQL-level filter is the mechanism that makes that test pass.

**Why no trait `NoteRepository`:**

- ADR-010 §3 already rejected a trait for `TipRepository` for the same
  reasons that apply here: no in-memory test double, no polymorphic
  construction, only Testcontainers + production wiring. Adding a trait
  for `NoteRepository` would create an inconsistency with `TipRepository`
  and with the user-and-completion repositories that do not have traits
  either (only `HabitRepository` has a trait, because there is an
  `InMemoryHabitRepository` test double).

**Why `findSimilar` returns `IO[List[RetrievedTip]]`, not `IO[List[UserNote]]`:**

- `RetrievedTip(tip: HabitTip, similarityScore: Double)` is the existing
  Phase 3 wire type for "any retrieved item with a similarity score". The
  prompt-builder consumes `List[RetrievedTip]`. Returning `RetrievedTip`
  from both `TipRepository.findSimilar` and `NoteRepository.findSimilar`
  lets the deduplication step compare them apples-to-apples and lets the
  prompt builder consume them through one interface.
- The compromise is that the inner `HabitTip(id, content)` carries a note's
  `id` and `content` even when the source is a user note. This is
  acceptable for the PoC — the `tip` field is a structural carrier, not a
  semantic claim that the source is the curated corpus. A future PBI can
  introduce a polymorphic `Retrieved(content, score, source)` ADT if the
  type confusion becomes a problem; ADR-011 does not lock that further
  refactor.

### 2. `user_notes` lives in the Docker Compose init-script directory — not Liquibase

`infra/db/init/03_create_user_notes.sql` is a new init script mounted into
the Postgres container via the existing `./infra/db/init:/docker-entrypoint-initdb.d:ro`
bind in `docker-compose.yaml`. It runs once on first container startup,
after `01_enable_vector.sql` and `02_create_habit_tips.sql`. The Liquibase
changelog is not modified — the next free Liquibase slot remains 006,
reserved for genuine application-schema changes.

```sql
-- Run once on first container startup
-- Creates the per-user notes table for the Phase 4 multi-source RAG pipeline.
-- The vector(1536) column matches text-embedding-3-small dimensionality
-- established by EmbeddingClient (ADR-010 §2). See ADR-011.

CREATE TABLE IF NOT EXISTS user_notes (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    content    TEXT         NOT NULL,
    embedding  vector(1536) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS user_notes_user_id_idx
    ON user_notes (user_id);
```

**Why init script, not Liquibase, even though `user_notes` is application-owned:**

- The pgvector boundary established in ADR-010 §1 is by *table family*, not
  by ownership of writes. Every table that stores `vector(N)` columns lives
  in `infra/db/init/` because:
  1. `CREATE EXTENSION vector` must run before any `vector` column is
     defined, and the extension is created by the privileged Postgres init
     role — Liquibase runs as `habituser`, which lacks that permission in
     production-shaped environments.
  2. Embedding-table lifecycles are tied to the *embedding model* choice
     (changing `text-embedding-3-small` to a 3072-dim model would require
     a fresh table and a re-seed/re-embed run, which is a corpus-management
     operation, not a feature-schema one). Liquibase's "forward-only,
     never edit a changeset" model is a poor fit for that lifecycle.
  3. Keeping every `vector` table in the init-script directory presents
     a single, ordered "RAG infrastructure DDL" surface that Phase 5+ can
     extend without Liquibase ceremony.
- The trade-off: the `user_notes` table cannot be evolved by a Liquibase
  changeset. An additive change (a new column) would require either a
  hand-edited init script (which would only run on a fresh volume — not
  on existing developer environments) or a one-off Liquibase changeset
  in slot 006+ that performs `ALTER TABLE user_notes ...`. The first
  option requires `docker compose down -v` and a re-seed of `habit_tips`;
  the second mixes schema ownership in a way the init-script boundary
  was meant to avoid. Documented as a known limitation; a future ADR
  would resolve it if Phase 4 evolves the `user_notes` shape.
- The `user_id` column is *not* a foreign key to `users.id`. Cross-database
  FK to a Liquibase-owned table from an init-script-owned table would
  re-introduce the lifecycle coupling the boundary was meant to break,
  and it would force the init script to depend on `users` already
  existing (which it does not at first startup, because Liquibase has
  not yet run). The application layer (`NoteRoutes`) is the integrity
  surface — `userId` in the path is trusted unconditionally per the
  PBI's "Out of scope" list.

**Why the index lives in the same file as the table:**

- ADR-010 §1 colocates the `uq_habit_tips_content` index with the
  `habit_tips` table. The same pattern holds here. The init script is
  the single place to look for "what does the `user_notes` table contain
  on a fresh database".
- The index is `BTREE` on `user_id` only — sufficient for the
  `WHERE user_id = $userId` filter in `similaritySearchSql`. A composite
  HNSW index on `(user_id, embedding)` would be faster for very large
  corpora but is out of scope for the PoC; pgvector's default IVFFlat
  index is also out of scope.

### 3. Parallel retrieval — `parTupled` from `cats.syntax.parallel._`, named `def retrieveBoth(queryEmbedding, userId)` on `TipsRoutes`

The two retrievals run concurrently inside `TipsRoutes` via `parTupled`:

```scala
import cats.syntax.parallel._

private val TIPS_TOP_K:  Int = 2
private val NOTES_TOP_K: Int = 2

// Both retrievals are independent IO operations with no shared state.
// Running them in parallel with `parTupled` from cats.syntax.parallel
// halves the worst-case retrieval latency compared to a sequential
// `.flatMap` chain. Cats IO `parTupled` runs both IOs concurrently on
// the same compute pool the rest of the request already uses, so no
// extra thread is allocated. HikariCP's connection pool (sized 10 in
// DatabaseConfig) absorbs the two concurrent queries without contention,
// the same way the analysis endpoint absorbs eight concurrent context
// queries (ADR-009 §5).
def retrieveBoth(
    queryEmbedding: Vector[Float],
    userId:         Long
): IO[(List[RetrievedTip], List[RetrievedTip])] =
  (
    tipRepo.findSimilar(queryEmbedding, TIPS_TOP_K),
    noteRepo.findSimilar(userId, queryEmbedding, NOTES_TOP_K)
  ).parTupled
```

**Why `parTupled` (not `mapN`, not `parMapN`, not sequential `flatMap`):**

- `mapN` on a tuple of `IO`s runs them sequentially under cats-effect's
  default `Monad`-based applicative — i.e. it gives no parallelism. It is
  rejected on correctness grounds (the AC requires concurrency).
- `parMapN` runs the two `IO`s in parallel and combines them via a
  function. Equivalent to `parTupled.map(f)`. We do not need the inline
  combining function — the for-comprehension destructures the tuple at
  the next step. `parTupled` is the smaller-surface idiom.
- Sequential `.flatMap` is rejected by AC-7 ("Sequential chaining
  (`.flatMap`) must not be used for these two calls").
- `Future`, `Thread`, and ZIO are explicitly forbidden by the HARD LIMITS
  block.

**Why a parameterised `def`, not a `val`:**

- AC-7 requires `def retrieveBoth(queryEmbedding: Vector[Float],
  userId: Long): IO[(List[RetrievedTip], List[RetrievedTip])]` — i.e.
  parameters, not closure capture.
- A `val retrieveBoth` would have to close over `queryEmbedding` and
  `userId` from the request scope, which means the binding has to be
  reconstructed per request (a `val` inside a method body is fine, but
  it cannot be a class-level `val`). A class-level `val` would either
  capture the wrong values or require thread-local state — both are
  anti-patterns.
- A parameterised `def` lets *any* future caller (e.g. a hypothetical
  evaluation endpoint, a debugging route, a test) invoke `retrieveBoth`
  directly without reconstructing the parallel composition. The Phase 4
  brief originally relied on this for the eval endpoint; even though
  the eval endpoint is removed (§7 below), the parameterised def is
  retained because it is the cleaner shape and the AC mandates it.
- The `def` is `private` to `TipsRoutes` because `TipsRoutes` is the
  only caller. If a future PBI introduces a second caller, the `def`
  can be hoisted to a service or made package-private — that is a
  separate ADR.

**Why on `TipsRoutes` and not on a separate service:**

- The Phase 3 `TipsRoutes` already composes the full request lifecycle in
  one for-comprehension. Hoisting `retrieveBoth` into a service would
  require either (a) a new `RetrievalService` interface plus an
  implementation plus DI wiring in `AppResources` or (b) adding a method
  to `AnalyticsService` (which is about analytics, not retrieval). Both
  are larger surface than this PBI requires. The Phase 4 deliverable is
  a working multi-source pipeline, not a clean-room rebuild of the
  Phase 3 architecture.
- A future PBI that introduces a second retrieval consumer can extract
  `retrieveBoth` into a service object at that point. ADR-011 does not
  lock the placement permanently.

### 4. Deduplication — pure `Deduplication` object, score-diff < 0.05 AND word-overlap > 0.8 thresholds, higher score wins

`com.habittracker.service.Deduplication` is a Scala `object` (no
constructor, no state) with three methods:

```scala
object Deduplication {

  private val SCORE_DIFF_THRESHOLD: Double = 0.05
  private val WORD_OVERLAP_THRESHOLD: Double = 0.8

  def deduplicate(
      tips:  List[RetrievedTip],
      notes: List[RetrievedTip]
  ): (List[RetrievedTip], List[RetrievedTip]) = ...

  private[service] def wordOverlapRatio(a: String, b: String): Double = ...
  private[service] def isDuplicate(a: RetrievedTip, b: RetrievedTip): Boolean = ...
}
```

**Why these thresholds (0.05 score diff, 0.8 word overlap):**

- The PBI specifies the exact values (AC-26): "Two items are considered
  duplicates if and only if their similarity scores differ by less than
  0.05 AND their word overlap ratio exceeds 0.8". The values are not up
  for debate; the ADR records *why* they are reasonable so a future PBI
  can revisit them with data.
- 0.05 score diff: cosine similarity scores in this corpus typically span
  a 0.1-0.3 range across the top-K results. A 0.05 threshold means "the
  two items are essentially indistinguishable on semantic similarity",
  which is the necessary condition for considering them duplicates. A
  much smaller threshold (e.g. 0.01) would over-filter; a much larger
  threshold (e.g. 0.2) would conflate unrelated items.
- 0.8 word overlap: `overlap = sharedWords / max(wordsA.size, wordsB.size)`
  measures literal lexical overlap. 0.8 means at least four-fifths of the
  longer item's words also appear in the shorter one — a strong signal
  of paraphrase or copy. Below 0.8 we expect genuinely different items
  that happen to score similarly.
- The `AND` between the two conditions is conservative: an item must be
  both semantically and lexically very close to be deduplicated. False
  negatives (missed duplicates) are preferable to false positives
  (dropping a genuinely different item) for a PoC.
- These thresholds are PoC defaults, easily tuned, and require no
  training data. A production system would A/B test them or learn them.
  The PBI explicitly notes that learned dedup is out of scope.

**Why a pure function (no `F[_]`, no `IO`):**

- AC-9 mandates purity: "It contains no `F[_]`, no `IO`, and no `Async`
  constraint. It is a pure referentially transparent function."
- Purity makes the six unit tests trivially writable (no IO runtime
  setup, no resource management, no Docker). The Reviewer can read the
  algorithm end-to-end without scanning for hidden side effects.
- The deduplication is a *logical* operation — it does not need to do
  IO, log, or fail. Constraining it to pure Scala makes that explicit
  in the type signature.
- A future PBI that wants to log dedup decisions or persist dropped
  items can introduce a wrapping service that consumes the pure
  function's output. The pure core remains.

**Why "higher score wins" tie-break:**

- When a duplicate pair `(a, b)` is detected, the item with the larger
  `similarityScore` is kept and the smaller is dropped. This corresponds
  to "the more relevant retrieval source for this query". If the corpus
  tip and the personal note tie exactly, the `tips` list's item is kept
  by the algorithm's traversal order — the PBI does not pin a specific
  rule for that case, and the implementation should be deterministic
  (e.g. always keep the `tips` side on tie).

**Algorithm sketch (Developer to implement; reference logic only):**

```scala
def deduplicate(
    tips:  List[RetrievedTip],
    notes: List[RetrievedTip]
): (List[RetrievedTip], List[RetrievedTip]) = {
  // For every tip, find any note that is a duplicate; if the tip's score
  // is higher (or equal), drop the matching note(s). Otherwise drop the
  // tip. Return the surviving items in their original relative order
  // within each list.
  val notesToDrop = scala.collection.mutable.Set.empty[Int]
  val tipsToDrop  = scala.collection.mutable.Set.empty[Int]

  for {
    (t, ti) <- tips.zipWithIndex
    (n, ni) <- notes.zipWithIndex
    if isDuplicate(t, n)
  } {
    if (t.similarityScore >= n.similarityScore) notesToDrop += ni
    else tipsToDrop += ti
  }

  val survivingTips  = tips.zipWithIndex.collect  { case (t, i) if !tipsToDrop(i)  => t }
  val survivingNotes = notes.zipWithIndex.collect { case (n, i) if !notesToDrop(i) => n }
  (survivingTips, survivingNotes)
}
```

The `mutable.Set` is *internal* to the function — the function is still
pure with respect to its inputs and outputs (no observable side effects).
A fully immutable implementation using `foldLeft` is acceptable and
preferred if the Developer can write one cleanly; the algorithmic
contract is what AC-10's six unit tests verify.

### 5. `RagLogger` — scores and counts only, stdout via `Async[F].delay(println)`, never the content of any tip or note

`com.habittracker.observability.RagLogger` is a Scala `object` with one
method:

```scala
object RagLogger {

  /** Logs the metadata of a single retrieval round to stdout.
    *
    * Format (single line):
    *   RAG userId=X externalCount=N personalCount=M
    *       topExternalScore=0.87 topPersonalScore=0.91
    *
    * Privacy contract: this method MUST NOT log the `content` field of any
    * RetrievedTip. The corpus tips are non-sensitive but the personal notes
    * may contain PII (the user types them as free text). Logging only the
    * count and the top score keeps the log line useful for observability
    * (retrieval-quality drift, empty-result alarms) without leaking
    * note content. */
  def logRetrieval[F[_]: Async](
      userId: Long,
      tips:   List[RetrievedTip],
      notes:  List[RetrievedTip]
  ): F[Unit] = {
    val externalCount = tips.size
    val personalCount = notes.size
    val topExternal   = tips.headOption.map(_.similarityScore).getOrElse(0.0)
    val topPersonal   = notes.headOption.map(_.similarityScore).getOrElse(0.0)
    Async[F].delay {
      println(
        f"RAG userId=$userId%d externalCount=$externalCount%d personalCount=$personalCount%d " +
        f"topExternalScore=$topExternal%.2f topPersonalScore=$topPersonal%.2f"
      )
    }
  }
}
```

**Why scores and counts only — never content:**

- AC-14 mandates this contract: "The `content` field of any `RetrievedTip`
  is never written to any log statement."
- Personal notes (`user_notes.content`) are user-supplied free text and
  may contain PII (medical conditions, names of family members, location
  data). Even the curated `habit_tips` content is not logged, because a
  log line that includes content for tips but not for notes invites
  reviewer drift later ("why don't we just log the note too?").
- The retrieval-quality signal the Reviewer cares about is the score
  distribution and the count. Top score and count are sufficient to
  detect: (a) empty results (count = 0); (b) low-confidence retrieval
  (top score below 0.7 say); (c) source imbalance (one source returns
  many results, the other none).
- The Reviewer is instructed to read the actual log statements in
  `RagLogger.scala` and verify by inspection. This ADR backs that check.

**Why `Async[F].delay(println)` and stdout, not log4cats / SLF4J:**

- The brief explicitly says "stdout only (via `Async[F].delay(println(...))`)"
  (AC-14). Logging frameworks would add configuration surface
  (logback.xml, log4cats imports, MDC) that the PoC does not need.
- `Async[F].delay` is the cats-effect-native way to lift a side effect
  into `F`. It composes cleanly into the for-comprehension in
  `TipsRoutes` (`_ <- RagLogger.logRetrieval(userId, tips, notes)`).
- The polymorphic `F[_]: Async` signature mirrors `EmbeddingClient.embed`
  and `AnthropicClient.complete`. It is always called as
  `RagLogger.logRetrieval[IO](...)` at the route call site.

**Why no error handling in `RagLogger`:**

- `println` does not throw under normal conditions. If stdout is closed
  (e.g. detached terminal in production), the runtime swallows the error.
  Adding a `try/recover` would invent a logging strategy without a need.
- A future PBI that introduces structured logging (log4cats or otherwise)
  can replace `RagLogger.logRetrieval` with a richer implementation
  behind the same call site. ADR-011 does not lock the implementation
  beyond the privacy contract.

### 6. `TipsResponse` breaking change — `tips` becomes `externalTips`, `personalNotes` is added; migration is a single rename across the codebase

The Phase 3 wire shape is:

```scala
final case class TipsResponse(tips: List[RetrievedTip], narrative: String)
```

The Phase 4 wire shape is:

```scala
final case class TipsResponse(
    externalTips:  List[RetrievedTip],
    personalNotes: List[RetrievedTip],
    narrative:     String
)
```

**Why a breaking change, not an additive one:**

- An additive shape `(tips, personalNotes, narrative)` would keep `tips`
  as the alias for the corpus source and add `personalNotes` for the
  user-notes source. The Phase 4 brief deliberately rejected this
  because the field name `tips` is ambiguous in a multi-source world —
  a reviewer reading the JSON cannot tell whether `tips` includes notes
  or only the corpus. Renaming `tips` → `externalTips` makes the source
  explicit at the wire boundary.
- The PoC has no external consumers of the API (the frontend is not yet
  wired). The cost of the breaking rename is purely internal: tests
  that decode `TipsResponse`, the OpenAPI spec, and the model itself.

**Migration approach (Developer must follow this exact order):**

1. Update `Analytics.scala`: rename `tips` to `externalTips`, add
   `personalNotes`.
2. Update `AnalyticsCodecs.scala`: the existing semiauto
   `tipsResponseEncoder` / `tipsResponseDecoder` re-derive automatically
   from the new case class shape on recompile. **No manual edit is
   required to the codec lines themselves**, but the Developer must
   confirm the import list includes `TipsResponse` (it already does).
3. Update `TipsRoutes.scala`: replace the Phase 3
   `TipsResponse(tips = retrieved, narrative = narrative)` construction
   with the Phase 4 `TipsResponse(externalTips = tips, personalNotes =
   notes, narrative = narrative)` after the dedup step.
4. Update OpenAPI spec: `TipsResponse` schema gains `personalNotes`,
   `tips` is renamed to `externalTips`, both arrays are required.
5. Search the test tree for the literal substring `TipsResponse.tips`
   or `.tips` on a `TipsResponse` value. **A grep of the current test
   tree finds zero matches** — no Phase 3 test currently asserts on
   `TipsResponse.tips` directly. The breaking rename is therefore a
   model + codec + route + OpenAPI change with no test code edits
   required. The PBI permits Phase 3 test edits if any are needed
   (AC-22), but in practice none are.

**Why semiauto codec re-derivation is safe here:**

- ADR-009 §8 already established that adding a field to a case class
  re-derives the semiauto codec on recompile. The same applies to
  renaming a field. The encoded JSON is `{externalTips: [...],
  personalNotes: [...], narrative: "..."}` — the field names match the
  case class field names exactly. No manual `Encoder.forProductN`
  override is needed.
- The Phase 4 codec change is therefore a *no-op edit* in
  `AnalyticsCodecs.scala`: the lines `implicit val tipsResponseEncoder
  = deriveEncoder[TipsResponse]` and the matching decoder line are
  already correct; they recompile against the new shape.

### 7. Eval endpoint (`POST /users/{userId}/habits/tips/evaluate`) is removed — out of scope for this PoC phase

The Phase 4 brief includes a third deliverable:
`POST /users/{userId}/habits/tips/evaluate` returning `EvalResponse(passed,
foundKeywords, narrative)`. The engineer removed this per decision
2026-04-29.

**Why removed:**

- The eval endpoint runs the full RAG pipeline on every call (embedding,
  parallel retrieval, dedup, LLM completion) and then performs a keyword
  match on the narrative. The first half is identical to `GET /tips`;
  the second half is a thin assertion that produces a boolean.
- A keyword-match-passes-narrative is not a meaningful RAG quality
  metric. Production RAG eval uses a labelled question set, recall@K,
  groundedness scoring, and an LLM-as-judge — none of which are in
  scope for this PoC.
- Persisting eval results was already out of scope ("Eval results are
  returned but not stored" — `docs/future_improvements.md`). Without
  persistence, the endpoint provides no longitudinal signal — each call
  is a one-shot keyword check.
- The PoC's learning goals (multi-source retrieval, dedup, parallel IO,
  observability) are met without the eval endpoint. Including it would
  add `EvalRequest`, `EvalResponse`, codecs, route, integration tests,
  OpenAPI surface, and three more acceptance criteria — all for a
  feature with no production analogue.

**Consequences of removal:**

- `EvalRequest` and `EvalResponse` are not added to `Analytics.scala`.
- No `evalRequestEncoder/decoder`, `evalResponseEncoder/decoder` in
  `AnalyticsCodecs`.
- No new route case in `TipsRoutes` for `POST .../tips/evaluate`.
- The `parameterised def retrieveBoth` is retained (§3) — it is still
  the cleaner shape and AC-7 mandates it; the original justification
  ("any future caller can invoke `retrieveBoth` directly") now applies
  to a hypothetical caller, not a present one.
- `docs/future_improvements.md` will note "EVAL PERSISTENCE" as a
  future improvement (per the PBI AC-29). This serves as the breadcrumb
  for a future PBI to revisit eval with a labelled dataset and storage.

### 8. `PromptBuilder` — `personalNotesSection` added, `build` signature widened to `(ctx, tips = Nil, notes = Nil)` — backward compatible

`PromptBuilder` gains one new section method and the `build` method's
signature widens to accept `notes`:

```scala
// Phase 4: personal-notes section, parallel to retrievedContextSection
// but labelled "YOUR PAST NOTES:" so the LLM can distinguish curated
// corpus tips from the user's own historical notes.
def personalNotesSection(notes: List[RetrievedTip]): String =
  if (notes.isEmpty) ""
  else {
    val lines = notes.map { rn => s"- ${rn.tip.content}" }
    "YOUR PAST NOTES:\n" + lines.mkString("\n")
  }

// Phase 4 widened signature — both `tips` and `notes` default to Nil so
// the AnalysisRoutes call site `PromptBuilder.build(ctx)` and any
// hypothetical Phase 3 caller `PromptBuilder.build(ctx, tips)` continue
// to compile.
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

**Why the `"YOUR PAST NOTES:"` label is distinct from `"RELEVANT TIPS:"`:**

- AC-12 mandates the label. The existing
  `retrievedContextSection` uses `"Relevant habit-science tips retrieved
  for this user (use as supporting evidence, not verbatim):"` as its
  preamble — already distinct from the new `"YOUR PAST NOTES:"` header.
  The two headers signal to the LLM that one source is third-party
  curated content and the other is the user's own writing; the prompt
  framing should treat them differently (the Anthropic system prompt
  already says "Always reference specific habit names and data points",
  which applies to both, but the distinct headers let a future system
  prompt revision instruct the LLM to give greater authority to the
  user's own notes).

**Why both defaults are `Nil`:**

- Backward compatibility with `AnalysisRoutes.build(ctx)` is mandated by
  AC-13 ("Calling `build(ctx)` or `build(ctx, tips)` compiles without
  modification."). The two defaults preserve the existing call site.
- The Phase 3 regression is also preserved: `build(ctx, tips = Nil,
  notes = Nil)` filters both retrieved sections out, so the output
  matches the Phase 2 `build(ctx)` output for the same `ctx`. AC-27
  pins this regression guard.

**Why the section method order in `build`:**

- `personalNotesSection` is appended after `retrievedContextSection`. The
  ordering matters because the LLM reads the prompt top-to-bottom and
  the closing context (the user's own notes) anchors the conversational
  framing. A future ADR could revisit if user feedback shows the LLM
  over-weights one source.
- The eight section methods are now: streak, dayPattern, ranking,
  timeOfDay, correlation, momentum, retrievedContext, personalNotes.
  The `.filter(_.nonEmpty)` ensures empty sections do not produce
  blank-line gaps in the assembled prompt.

### 9. `NoteRoutes` is a separate route class — POST /users/{userId}/habits/notes only, registered between BatchCompletionRoutes and HabitRoutes

`com.habittracker.http.NoteRoutes` is a new class with one route case:

```scala
final class NoteRoutes(noteRepo: NoteRepository) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "users" / LongVar(userId) / "habits" / "notes" =>
      req.as[NoteRequest].flatMap { body =>
        for {
          embedding <- EmbeddingClient.embed[IO](body.content)
          note      <- noteRepo.insert(userId, body.content, embedding)
          result    <- Created(note)
        } yield result
      }.handleErrorWith { case _: DecodeFailure =>
        BadRequest(ErrorResponse("Malformed request body"))
      }
  }
}
```

**Why a separate class, not an extension of `TipsRoutes`:**

- `TipsRoutes` is about retrieval; `NoteRoutes` is about ingestion.
  Conflating both into one class would tangle two different LLM use
  cases (the daily-tip RAG retrieval call and the embed-on-write
  ingestion call). The two endpoints use *different* request shapes,
  *different* response shapes, and *different* error semantics. The
  Phase 1/2 sibling-class precedent (`InsightsRoutes` /
  `AnalysisRoutes` per ADR-009 §7) holds here.
- AC-20 explicitly mandates a separate `NoteRoutes` class.
- `NoteRoutes` constructor takes only `noteRepo: NoteRepository`; it
  does not need `analyticsService` or `tipRepo`. Keeping the dependency
  surface minimal makes the class easier to test and review.

**Why the route order is `... → BatchCompletionRoutes → NoteRoutes → HabitRoutes → ...`:**

- Architect Memory locks this order. `NoteRoutes` is appended after
  `BatchCompletionRoutes` and before `HabitRoutes` so the
  user-and-habits CRUD surface remains last.
- http4s pattern matching on `/users/{userId}/habits/notes` does not
  collide with `/users/{userId}/habits/{habitId}` because `notes` is a
  literal path segment that fails to parse as `UUIDVar`. However, to
  make the precedence visible in source, the routes are registered in
  the order recorded above.

**Why `EmbeddingClient.embed[IO]` is called directly from the route:**

- ADR-008 §2 / ADR-010 §2 already locked this rule: live API clients are
  called directly at the route call site, no wrapping service. The
  POST /notes flow is "embed then insert" — the same shape as the
  embed-then-store flow in `SeedTips`. There is no business logic to
  hoist into a service.
- The trade-off is the same as `TipsRoutes`: no unit test for the
  route. The integration test (AC-19) seeds a user, posts a note, and
  asserts the round-trip via `GET /tips`.

**HTTP 201 Created semantics:**

- AC-2 mandates HTTP 201 with the `UserNote` body. http4s's `Created(note)`
  helper produces exactly that — status code 201, body
  `{"id": ..., "userId": ..., "content": "...", "createdAt": "..."}`.
- The Location header is not included. The PBI does not require it, and
  the resource's URL has no GET endpoint to point at (this PoC does not
  expose `GET /notes/{noteId}`).

### 10. `Analytics.scala` and `AnalyticsCodecs.scala` extensions — `UserNote`, `NoteRequest`, `TipsResponse` rename

`Analytics.scala` gains two new case classes and renames one field:

```scala
// Phase 4 — persisted in user_notes table, returned by POST /notes
final case class UserNote(
    id:        Long,
    userId:    Long,
    content:   String,
    createdAt: java.time.Instant
)

// Phase 4 — request body for POST /users/{userId}/habits/notes
final case class NoteRequest(content: String)

// TipsResponse becomes — breaking change from Phase 3
final case class TipsResponse(
    externalTips:  List[RetrievedTip],
    personalNotes: List[RetrievedTip],
    narrative:     String
)
```

`AnalyticsCodecs.scala` gains two new pairs of semiauto codecs. The
existing `tipsResponseEncoder` / `tipsResponseDecoder` lines re-derive
on recompile and need no manual edit — see §6.

```scala
implicit val userNoteEncoder:    Encoder[UserNote]    = deriveEncoder[UserNote]
implicit val userNoteDecoder:    Decoder[UserNote]    = deriveDecoder[UserNote]
implicit val noteRequestEncoder: Encoder[NoteRequest] = deriveEncoder[NoteRequest]
implicit val noteRequestDecoder: Decoder[NoteRequest] = deriveDecoder[NoteRequest]
```

**Why semiauto and not auto:**

- Architect Memory locks this rule: "Circe: semiauto only — never auto."
  Auto-derivation is a maintenance hazard (codecs disappear silently
  when imports drift). Semiauto makes every codec explicit at one
  declaration site.
- `Instant` codecs are provided transitively by `io.circe.Encoder.encodeInstant`
  / `Decoder.decodeInstant` from circe-core. No extra import needed.

### 11. OpenAPI updates — additive plus `TipsResponse` rename, two new schemas, one new path

`backend/src/main/resources/openapi/openapi.yaml` gains:

- `UserNote` schema (id: int64, userId: int64, content: string,
  createdAt: date-time).
- `NoteRequest` schema (content: string).
- `POST /users/{userId}/habits/notes` path with `createUserNote`
  operationId, request body `NoteRequest`, response 201
  `UserNote`.
- `TipsResponse` schema rewritten: `tips` removed, `externalTips` and
  `personalNotes` added (both arrays of `RetrievedTip`), all three
  fields required.

The Liquibase changelog is not modified. The `user_notes` table is
infrastructure (§2).

### 12. Build remains Gradle, no new dependencies

The Phase 4 brief mentions sbt and munit-cats-effect in places —
documentation drift, corrected here as in ADR-008 §7, ADR-009 §10, and
ADR-010 §12. All Developer commands use `./gradlew compileScala` and
`./gradlew test`.

**No new dependencies are added.** The required modules are already on
the classpath:

- `cats.syntax.parallel._` and `parTupled` come from cats 2.x, transitive
  via cats-effect 3.5.4.
- `cats.effect.Async` and `Async[F].delay` come from cats-effect 3.5.4.
- Doobie + pgvector wire format are reused from `TipRepository`
  unchanged.
- ScalaTest, Testcontainers, sttp, circe — all already in `build.gradle`.

If the Developer finds a missing dependency, it must be flagged as a
blocker in the technical plan, not silently added.

---

## Consequences

**Easier:**

- Adding a Phase 5 third retrieval source (e.g. shared
  community-contributed tips) is a parallel `XRepository`, a parallel
  section method on `PromptBuilder`, and a third `parTupled` slot in
  `retrieveBoth`. The deduplication function generalises naturally to
  three sources by chaining pairwise comparisons.
- The `user_notes` table sits next to `habit_tips` in
  `infra/db/init/`. Future embedding-backed corpora go in the same
  directory.
- The `RagLogger` privacy contract is one method, one file. A future
  swap to log4cats or structured logging changes the implementation
  behind the same call site without touching `TipsRoutes`.
- `NoteRoutes` is small (one case branch) and reusable: a future PBI
  that adds `GET /notes` or `DELETE /notes/{id}` extends the same
  class without touching `TipsRoutes` or `AppResources` route
  composition.
- The `parTupled` pattern in `retrieveBoth` matches the analytics
  endpoint's parallel context assembly (ADR-009 §5). Engineers
  familiar with one path read the other in the same idiom.

**Harder / trade-offs:**

- The `user_notes` table cannot be evolved by a Liquibase changeset.
  An additive change requires either a hand-edited init script (only
  runs on fresh volumes) or a one-off Liquibase changeset that mixes
  schema ownership across the boundary. Documented as a known
  limitation; a future ADR resolves it if Phase 4 evolves the shape.
- `NoteRepository.findSimilar` returns `List[RetrievedTip]` carrying
  a `HabitTip(id, content)` whose `id` is actually a note id. The
  type confusion is a known compromise with the existing
  `RetrievedTip` shape (§1). A future PBI may introduce a polymorphic
  `Retrieved(content, score, source: Source)` ADT.
- The deduplication thresholds (0.05 / 0.8) are unvalidated PoC
  defaults. Without an eval harness (the eval endpoint is removed,
  §7), the only way to tune them is manual inspection of retrieved
  results. A future PBI that adds eval persistence can revisit.
- `RagLogger` writes to stdout via `println`. In production this would
  be replaced by structured logging; in the PoC, log lines are mixed
  with http4s server output. Acceptable for the learning goal.
- The eval endpoint is removed. Engineers see a working multi-source
  RAG pipeline but no way to assert quality programmatically. Manual
  inspection of `personalNotes` content in `GET /tips` responses is
  the proxy.
- Two startup-failure surfaces remain (`ANTHROPIC_API_KEY`,
  `OPENAI_API_KEY`). Either key missing aborts boot. Documented
  trade-off carried over from ADR-010.

**Locked in:**

- Every `vector(N)` table in this codebase lives in `infra/db/init/`,
  not Liquibase. Phase 5+ embedding-backed corpora extend the same
  directory.
- `NoteRepository(xa: Transactor[IO])` with `IO[_]` returns and named
  `similaritySearchSql` is the authoritative shape. Future
  embedding-backed repositories follow the same pattern.
- Parallel retrieval uses `parTupled` from `cats.syntax.parallel._`.
  `Future`, `Thread`, ZIO are forbidden.
- `retrieveBoth` is a parameterised `def`. Any future caller invokes
  it directly, not via a class-level `val` that captures request
  state.
- Deduplication is a pure function. A future logging or persistence
  wrapper consumes its output but does not entangle it with `IO`.
- `RagLogger` logs scores and counts only; the privacy contract is
  load-bearing for future log-format changes.
- `TipsResponse` wire shape is `(externalTips, personalNotes,
  narrative)`. Any future field rename or addition is itself a
  breaking change requiring its own ADR.
- The eval endpoint is *deferred*, not cancelled. A future PBI may
  reintroduce it with a labelled question set, persistence, and an
  LLM-as-judge metric — that PBI must produce its own ADR rather
  than reusing this one.
- `PromptBuilder.build(ctx, tips: List[RetrievedTip] = Nil, notes:
  List[RetrievedTip] = Nil): String` is the authoritative signature.
  Future sections append before, not in the middle of, the existing
  eight-section list.
- Build remains Gradle. No new runtime or test dependency is added in
  Phase 4.
