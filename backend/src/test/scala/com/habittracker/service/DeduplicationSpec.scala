package com.habittracker.service

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import com.habittracker.model.{HabitTip, RetrievedTip}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

@RunWith(classOf[JUnitRunner])
class DeduplicationSpec extends AnyWordSpec with Matchers {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def tip(id: Long, content: String, score: Double): RetrievedTip =
    RetrievedTip(HabitTip(id, content), score)

  // ---------------------------------------------------------------------------
  // deduplicate
  // ---------------------------------------------------------------------------

  "Deduplication.deduplicate" should {

    "retain only one item when both lists contain identical content" in {
      // Score diff = 0.90 - 0.88 = 0.02 < 0.05; word overlap = 1.0 > 0.8 => duplicate
      val tips  = List(tip(1L, "Stack your habits", 0.90))
      val notes = List(tip(7L, "Stack your habits", 0.88))
      val (survivingTips, survivingNotes) = Deduplication.deduplicate(tips, notes)
      // Total surviving items should be exactly 1
      (survivingTips.length + survivingNotes.length) shouldBe 1
      // The higher-scored item (tips, score 0.90) should be the survivor
      survivingTips should contain(tips.head)
      survivingNotes shouldBe Nil
    }

    "return both lists unchanged when there is no overlap" in {
      val tips  = List(tip(1L, "Stack your habits every morning", 0.90))
      val notes = List(tip(7L, "Exercise improves sleep quality deeply", 0.85))
      val result = Deduplication.deduplicate(tips, notes)
      result shouldBe (tips, notes)
    }

    "retain the higher-scored duplicate and remove the lower-scored one" in {
      // note has higher score than tip; score diff = 0.93 - 0.90 = 0.03 < 0.05 => duplicate
      val tips  = List(tip(1L, "Stack your habits every morning", 0.90))
      val notes = List(tip(7L, "Stack your habits every morning", 0.93))
      val (survivingTips, survivingNotes) = Deduplication.deduplicate(tips, notes)
      // The note with score 0.93 should survive; the tip with 0.90 should be dropped
      survivingTips shouldBe Nil
      survivingNotes should contain(notes.head)
      (survivingTips.length + survivingNotes.length) shouldBe 1
    }
  }

  // ---------------------------------------------------------------------------
  // wordOverlapRatio
  // ---------------------------------------------------------------------------

  "Deduplication.wordOverlapRatio" should {

    "compute expected ratio for known inputs" in {
      // shared = {the, quick, brown}, max = 4 => ratio = 3/4 = 0.75
      val r = Deduplication.wordOverlapRatio("the quick brown fox", "the quick brown dog")
      r should be > 0.74
      r should be < 0.76
    }
  }

  // ---------------------------------------------------------------------------
  // isDuplicate
  // ---------------------------------------------------------------------------

  "Deduplication.isDuplicate" should {

    "return true when both score-diff and word-overlap conditions are met" in {
      val a = tip(1L, "stack habits", 0.90)
      val b = tip(2L, "stack habits", 0.91)
      // score diff = 0.01 < 0.05 AND word overlap = 1.0 > 0.8
      Deduplication.isDuplicate(a, b) shouldBe true
    }

    "return false when score difference is too large" in {
      val a = tip(1L, "stack your habits every day", 0.90)
      val b = tip(2L, "stack your habits every day", 0.20)
      // score diff = 0.70 > 0.05 => not a duplicate regardless of content
      Deduplication.isDuplicate(a, b) shouldBe false
    }
  }
}
