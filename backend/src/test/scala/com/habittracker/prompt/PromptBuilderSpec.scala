package com.habittracker.prompt

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import com.habittracker.model.{HabitContext, HabitTip, RetrievedTip}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

@RunWith(classOf[JUnitRunner])
class PromptBuilderSpec extends AnyWordSpec with Matchers {

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private val habitId1 = UUID.fromString("11111111-0000-0000-0000-000000000001")
  private val habitId2 = UUID.fromString("22222222-0000-0000-0000-000000000002")

  /** A fully populated HabitContext — all sections non-empty. */
  private val fullyPopulated = HabitContext(
    userId = 1L,
    streaks = Map(habitId1 -> 5, habitId2 -> 3),
    completionByDay = Map(
      "Monday"    -> 0.8,
      "Tuesday"   -> 0.6,
      "Wednesday" -> 0.5,
      "Thursday"  -> 0.4,
      "Friday"    -> 0.3,
      "Saturday"  -> 0.2,
      "Sunday"    -> 0.1
    ),
    consistencyRanking = List(("Run", 1.25), ("Meditate", 0.8)),
    timeOfDayPatterns = Map(
      "morning"   -> 0.5,
      "afternoon" -> 0.3,
      "evening"   -> 0.1,
      "night"     -> 0.1
    ),
    correlatedPairs = List(("Run", "Meditate", 0.8), ("Run", "Sleep", 0.4)),
    momentumScores = Map(habitId1 -> 0.13, habitId2 -> -0.07)
  )

  /** An empty HabitContext — all sections will produce "". */
  private val emptyCtx = HabitContext(
    userId = 1L,
    streaks = Map.empty,
    completionByDay = Map(
      "Monday"    -> 0.0,
      "Tuesday"   -> 0.0,
      "Wednesday" -> 0.0,
      "Thursday"  -> 0.0,
      "Friday"    -> 0.0,
      "Saturday"  -> 0.0,
      "Sunday"    -> 0.0
    ),
    consistencyRanking = List.empty,
    timeOfDayPatterns = Map(
      "morning"   -> 0.0,
      "afternoon" -> 0.0,
      "evening"   -> 0.0,
      "night"     -> 0.0
    ),
    correlatedPairs = List.empty,
    momentumScores = Map.empty
  )

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  "PromptBuilder" should {

    "expose HABIT_COACH_SYSTEM_PROMPT as a non-empty constant" in {
      PromptBuilder.HABIT_COACH_SYSTEM_PROMPT.nonEmpty shouldBe true
    }

    // -------------------------------------------------------------------------
    // streakSection
    // -------------------------------------------------------------------------

    "streakSection should return non-empty for a fully populated ctx" in {
      PromptBuilder.streakSection(fullyPopulated).nonEmpty shouldBe true
    }

    "streakSection should return empty string when streaks is empty" in {
      PromptBuilder.streakSection(emptyCtx) shouldBe ""
    }

    // -------------------------------------------------------------------------
    // dayPatternSection
    // -------------------------------------------------------------------------

    "dayPatternSection should return non-empty for a fully populated ctx" in {
      PromptBuilder.dayPatternSection(fullyPopulated).nonEmpty shouldBe true
    }

    "dayPatternSection should return empty string when all rates are 0.0" in {
      PromptBuilder.dayPatternSection(emptyCtx) shouldBe ""
    }

    // -------------------------------------------------------------------------
    // rankingSection
    // -------------------------------------------------------------------------

    "rankingSection should return non-empty for a fully populated ctx" in {
      PromptBuilder.rankingSection(fullyPopulated).nonEmpty shouldBe true
    }

    "rankingSection should return empty string when consistencyRanking is empty" in {
      PromptBuilder.rankingSection(emptyCtx) shouldBe ""
    }

    // -------------------------------------------------------------------------
    // timeOfDaySection
    // -------------------------------------------------------------------------

    "timeOfDaySection should return non-empty for a fully populated ctx" in {
      PromptBuilder.timeOfDaySection(fullyPopulated).nonEmpty shouldBe true
    }

    "timeOfDaySection should return empty string when all rates are 0.0" in {
      PromptBuilder.timeOfDaySection(emptyCtx) shouldBe ""
    }

    // -------------------------------------------------------------------------
    // correlationSection
    // -------------------------------------------------------------------------

    "correlationSection should return non-empty for a fully populated ctx" in {
      PromptBuilder.correlationSection(fullyPopulated).nonEmpty shouldBe true
    }

    "correlationSection should return empty string when correlatedPairs is empty" in {
      PromptBuilder.correlationSection(emptyCtx) shouldBe ""
    }

    // -------------------------------------------------------------------------
    // momentumSection
    // -------------------------------------------------------------------------

    "momentumSection should return non-empty for a fully populated ctx" in {
      PromptBuilder.momentumSection(fullyPopulated).nonEmpty shouldBe true
    }

    "momentumSection should return empty string when momentumScores is empty" in {
      PromptBuilder.momentumSection(emptyCtx) shouldBe ""
    }

    // -------------------------------------------------------------------------
    // build
    // -------------------------------------------------------------------------

    "build" should {

      "contain content from all 6 sections when ctx is fully populated" in {
        val result = PromptBuilder.build(fullyPopulated)
        result should include(PromptBuilder.streakSection(fullyPopulated))
        result should include(PromptBuilder.dayPatternSection(fullyPopulated))
        result should include(PromptBuilder.rankingSection(fullyPopulated))
        result should include(PromptBuilder.timeOfDaySection(fullyPopulated))
        result should include(PromptBuilder.correlationSection(fullyPopulated))
        result should include(PromptBuilder.momentumSection(fullyPopulated))
      }

      "filter out empty sections so blank lines do not appear in output" in {
        // Only streaks non-empty; all other sections empty
        val ctxStreaksOnly = emptyCtx.copy(
          streaks = Map(habitId1 -> 7)
        )
        val result = PromptBuilder.build(ctxStreaksOnly)
        // Result must equal streakSection alone — no extra blank separators
        result shouldBe PromptBuilder.streakSection(ctxStreaksOnly)
        // No triple newline from adjacent empty sections
        result should not include "\n\n\n"
      }
    }

    // -------------------------------------------------------------------------
    // Phase 3: retrievedContextSection
    // -------------------------------------------------------------------------

    "retrievedContextSection" should {

      "return a non-empty String for non-empty tips list" in {
        val tips = List(
          RetrievedTip(HabitTip(1L, "Stack your habits"), 0.9),
          RetrievedTip(HabitTip(2L, "Reduce friction"),   0.85)
        )
        PromptBuilder.retrievedContextSection(tips).nonEmpty shouldBe true
      }

      "return empty string for Nil without error" in {
        PromptBuilder.retrievedContextSection(Nil) shouldBe ""
      }
    }

    // -------------------------------------------------------------------------
    // Phase 3: build with tips
    // -------------------------------------------------------------------------

    "build with non-empty tips" should {

      "include the retrieved-context preamble in the output" in {
        val tips = List(
          RetrievedTip(HabitTip(1L, "Stack your habits"), 0.9)
        )
        val result = PromptBuilder.build(fullyPopulated, tips)
        result should include("Relevant habit-science tips retrieved for this user")
      }
    }

    "build without tips (default Nil)" should {

      "produce the same output as build(ctx, Nil) — regression guard" in {
        val withDefault = PromptBuilder.build(fullyPopulated)
        val withExplicitNil = PromptBuilder.build(fullyPopulated, Nil)
        withDefault shouldBe withExplicitNil
      }
    }

    // -------------------------------------------------------------------------
    // Phase 4: personalNotesSection
    // -------------------------------------------------------------------------

    "personalNotesSection" should {

      "return a non-empty String starting with 'YOUR PAST NOTES:' for non-empty notes list" in {
        val notes = List(
          RetrievedTip(HabitTip(1L, "I did better when I exercised in the morning"), 0.88)
        )
        val result = PromptBuilder.personalNotesSection(notes)
        result should startWith("YOUR PAST NOTES:")
        result should include("I did better when I exercised in the morning")
      }

      "return empty string for Nil without error" in {
        PromptBuilder.personalNotesSection(Nil) shouldBe ""
      }
    }

    // -------------------------------------------------------------------------
    // Phase 4: build with both tips and notes
    // -------------------------------------------------------------------------

    "build with both tips and notes" should {

      "include both section labels in the output" in {
        val tips = List(
          RetrievedTip(HabitTip(1L, "Stack your habits"), 0.9)
        )
        val notes = List(
          RetrievedTip(HabitTip(7L, "I did better when I exercised in the morning"), 0.88)
        )
        val result = PromptBuilder.build(fullyPopulated, tips = tips, notes = notes)
        result should include("Relevant habit-science tips retrieved for this user")
        result should include("YOUR PAST NOTES:")
      }
    }

    "build with empty notes (default Nil)" should {

      "match Phase 3 build output — regression guard for AC-27" in {
        val tips = List(
          RetrievedTip(HabitTip(1L, "Stack your habits"), 0.9)
        )
        PromptBuilder.build(fullyPopulated, tips, Nil) shouldBe
          PromptBuilder.build(fullyPopulated, tips)
      }
    }
  }
}
