# Developer Agent Memory
# Habit Tracker · Agent Swarm
# Last updated: Phase 4 retrospective (2026-04-30)

---

## HabitContext Field Types (CRITICAL — phase briefs have errors)

HabitContext (com.habittracker.model.Analytics) as of Phase 4:
  userId:             Long
  streaks:            Map[UUID, Int]                  ← UUID, not Long
  completionByDay:    Map[String, Double]
  consistencyRanking: List[(String, Double)]
  timeOfDayPatterns:  Map[String, Double]              = Map.empty
  correlatedPairs:    List[(String, String, Double)]   = Nil
  momentumScores:     Map[UUID, Double]                = Map.empty  ← UUID, not Long
  retrievedTips:      List[String]                     = Nil

Use Scala defaults for any new fields if frozen tests construct HabitContext with fewer args.

---

## sttp Content-Type Rule (CRITICAL — learnt from Phase 3 production bug)

In sttp v3, .body(string) adds Content-Type: text/plain. Setting the header BEFORE
.body() is overridden (replaceExisting=false default).

Always set Content-Type AFTER .body() with replaceExisting = true:
  basicRequest
    .post(uri"$url")
    .header("Authorization", s"Bearer $key")
    .body(bodyJson)
    .header("Content-Type", "application/json", replaceExisting = true)  // ← after body
    .response(asString)

Anthropic accepts text/plain (bug harmless there). OpenAI and most APIs are strict.
AnthropicClient is frozen — bug stays, harmless. All new sttp callers must follow this.

---

## Parallel Execution Pattern

parTupled — fixed set of independent IO calls:
  (io1, io2).parTupled  // requires import cats.syntax.parallel._

parTraverse — uniform collection:
  list.parTraverse { x => ... }

Phase 4 uses parTupled in TipsRoutes.retrieveBoth (tips + notes retrieval).
Phase 2 uses parTupled in AnalyticsService (4 aggregates) and parTraverse for per-habit fan-outs.

---

## Service Trait New-Method Pattern (CRITICAL)

New methods on service traits: concrete default IO.raiseError(new NotImplementedError(...))
— never abstract. Prevents breaking frozen test fakes. Real impl overrides it.

---

## Test Conventions

Use ScalaTest AnyWordSpec + @RunWith(classOf[JUnitRunner]). Not munit-cats-effect.

Testcontainers specs (require Docker):
  - @Ignore (active) + // requires Docker - run manually
  - postgres:17-alpine for standard specs
  - pgvector/pgvector:pg17 for specs needing the vector extension (TipRepositorySpec, NoteRepositorySpec)

Specs requiring Docker AND live API key:
  - @Ignore + // requires Docker AND OPENAI_API_KEY - run manually

Pure unit tests: no @Ignore, extend AnyWordSpec with Matchers.

RagLogger test capture: Console.withOut alone fails across cats-effect work-stealing pool.
Use a fresh single-threaded IORuntime per capture combined with System.setOut(ps).

---

## Circe Codecs

Use io.circe.generic.semiauto._ with deriveEncoder/deriveDecoder. Never generic.auto.
UUID KeyEncoder/KeyDecoder already defined in AnalyticsCodecs — do not redefine.
Add new codecs to AnalyticsCodecs.scala (analytics types) or CompletionCodecs.scala (completion types).

---

## Build Commands

./gradlew compileScala — after each new file group
./gradlew test         — when all files compile

---

## AppResources Wiring Pattern

Current route composition (do not break this order):
  new DocsRoutes().routes <+>
  new InsightsRoutes(analyticsService).routes <+>
  new AnalysisRoutes(analyticsService).routes <+>
  new TipsRoutes(analyticsService, tipRepo, noteRepo).routes <+>   ← 3 args as of Phase 4
  new BatchCompletionRoutes(completionSvc).routes <+>
  new NoteRoutes(noteRepo).routes <+>
  new HabitRoutes(habitService).routes <+>
  new HabitCompletionRoutes(completionSvc).routes

AnthropicClient.API_KEY_CHECK and EmbeddingClient.API_KEY_CHECK already wired.

---

## TipsResponse Fields (updated Phase 4)

Old (Phase 3): TipsResponse(tips: List[RetrievedTip], narrative: String)
New (Phase 4): TipsResponse(externalTips: List[RetrievedTip], personalNotes: List[RetrievedTip], narrative: String)

Zero existing test references to old .tips field were found at Phase 4 implementation time.
Any future code referencing TipsResponse must use externalTips and personalNotes.

---

## Files That Must Not Be Modified

  AnthropicClient.scala, EmbeddingClient.scala, InsightPrompt.scala,
  InsightsRoutes.scala, AnalysisRoutes.scala, BatchCompletionRoutes.scala

All Phase 1-4 test files are frozen except those explicitly requiring TipsResponse updates.

---

## Deduplication Algorithm (Deduplication.scala)

Pure object, no F[_] or IO. Thresholds:
  SCORE_DIFF_THRESHOLD  = 0.05 (strict <)
  WORD_OVERLAP_THRESHOLD = 0.8  (strict >)
Both conditions must hold for a duplicate. Higher score wins; tip side wins on tie.
wordOverlapRatio = sharedWords / max(wordsA.size, wordsB.size)

---

## Known Flaky Test

DoobieAnalyticsRepositorySpec — completionRateByDayOfWeek:
  TODO: incorrect on Wednesdays. @Ignore so no CI impact. Do not fix here.

---

## Known Tech Debt (accumulated)

1. HabitCompletionCodecsSpec — HabitCompletionResponse completedAt round-trip missing (Phase 2)
2. HabitCompletionCodecsSpec — BatchCompletionResponse/SkippedCompletion codec unit tests missing (Phase 3)
3. SeedTipsIdempotencySpec — add // NOTE: SeedTips.run is not called here comment (Phase 3)
4. PromptBuilder — UUID rendering instead of habit names in streakSection/momentumSection (Phase 2)
5. NoteRepository.similaritySearchSql — add one-line comment explaining it is a naming shim (Phase 4)
6. DeduplicationSpec — missing AND boundary test: overlap too low but score diff within threshold (Phase 4)
