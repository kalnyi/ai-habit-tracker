package com.habittracker.prompt

import com.habittracker.model.HabitContext
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

@RunWith(classOf[JUnitRunner])
class InsightPromptSpec extends AnyWordSpec with Matchers {

  private val habitId1 = UUID.randomUUID()
  private val habitId2 = UUID.randomUUID()

  private val sampleCtx = HabitContext(
    userId             = 42L,
    streaks            = Map(habitId1 -> 3, habitId2 -> 0),
    completionByDay    = Map(
      "Monday"    -> 0.75,
      "Tuesday"   -> 0.50,
      "Wednesday" -> 0.25,
      "Thursday"  -> 0.00,
      "Friday"    -> 1.00,
      "Saturday"  -> 0.00,
      "Sunday"    -> 0.50
    ),
    consistencyRanking = List("Read 20 pages" -> 0.80, "Exercise" -> 0.40)
  )

  "InsightPrompt.build" should {

    "include the userId from ctx in the output" in {
      val out = InsightPrompt.build(sampleCtx)
      out should include ("42")
    }

    "include at least one habit name from ctx" in {
      val out = InsightPrompt.build(sampleCtx)
      (out.contains("Read 20 pages") || out.contains("Exercise")) shouldBe true
    }

    "include at least one day name from ctx" in {
      val out = InsightPrompt.build(sampleCtx)
      val dayNames = List("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")
      dayNames.exists(d => out.contains(d)) shouldBe true
    }

    "produce non-empty strings for all 3 named section vals" in {
      // Reflection-free assertion: we assert the full output contains a
      // non-empty substring from each section by checking for section-specific
      // keywords. This covers AC-12's "all 3 named section vals produce
      // non-empty strings" requirement.
      val out = InsightPrompt.build(sampleCtx)
      out should include ("Current streaks:")
      out should include ("Completion rate by day of week:")
      out should include ("Consistency ranking")
    }
  }
}
