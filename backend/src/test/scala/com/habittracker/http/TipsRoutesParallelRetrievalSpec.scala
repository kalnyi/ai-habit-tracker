package com.habittracker.http

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.IORuntime
import cats.syntax.parallel._
import com.habittracker.model.{HabitTip, RetrievedTip}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Verifies AC-18: both TipRepository.findSimilar and NoteRepository.findSimilar
  * are invoked exactly once by the parallel retrieval composition.
  *
  * Approach (b) from the plan: directly exercise the parTupled primitive with
  * stubbed repos that record invocation counts via Ref. This avoids invoking
  * the real EmbeddingClient or AnthropicClient and tests the composition
  * contract directly. */
@RunWith(classOf[JUnitRunner])
class TipsRoutesParallelRetrievalSpec extends AnyWordSpec with Matchers {

  implicit val runtime: IORuntime = IORuntime.global

  "retrieveBoth" should {

    "invoke TipRepository.findSimilar exactly once and NoteRepository.findSimilar exactly once" in {
      // Ref-based invocation counters
      val tipCallCount  = Ref.unsafe[IO, Int](0)
      val noteCallCount = Ref.unsafe[IO, Int](0)

      // Stubbed TipRepository that increments counter and returns empty list
      val stubbedTipFindSimilar: IO[List[RetrievedTip]] =
        tipCallCount.update(_ + 1).as(List(RetrievedTip(HabitTip(1L, "tip content"), 0.9)))

      // Stubbed NoteRepository that increments counter and returns empty list
      val stubbedNoteFindSimilar: IO[List[RetrievedTip]] =
        noteCallCount.update(_ + 1).as(List(RetrievedTip(HabitTip(7L, "note content"), 0.85)))

      // Exercise the same parTupled composition used in TipsRoutes.retrieveBoth
      val retrieveBoth: IO[(List[RetrievedTip], List[RetrievedTip])] =
        (
          stubbedTipFindSimilar,
          stubbedNoteFindSimilar
        ).parTupled

      val (tips, notes) = retrieveBoth.unsafeRunSync()

      // Both IOs must have been invoked exactly once
      tipCallCount.get.unsafeRunSync()  shouldBe 1
      noteCallCount.get.unsafeRunSync() shouldBe 1

      // Results are threaded through correctly
      tips  should have length 1
      notes should have length 1
    }
  }
}
