package com.habittracker.observability

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.IO
import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import com.habittracker.model.{HabitTip, RetrievedTip}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.concurrent.ExecutionContext

@RunWith(classOf[JUnitRunner])
class RagLoggerSpec extends AnyWordSpec with Matchers {

  // Build a single-threaded IORuntime for each capture so that IO.delay
  // always executes on the calling thread where System.out and Console.out
  // are both redirected. The global runtime uses a work-stealing pool whose
  // threads may not see the per-test redirect.
  private def singleThreadedRuntime(): IORuntime = {
    val ec = ExecutionContext.fromExecutor(
      java.util.concurrent.Executors.newSingleThreadExecutor()
    )
    IORuntime(ec, ec, IORuntime.global.scheduler, () => (), IORuntimeConfig())
  }

  // Capture output of RagLogger.logRetrieval. Both System.out and the Scala
  // Console are redirected so the single-threaded IO fiber's println call
  // is captured regardless of which mechanism Predef.println resolves through.
  private def captureLogRetrieval(
      userId: Long,
      tips:   List[RetrievedTip],
      notes:  List[RetrievedTip]
  ): String = {
    val baos        = new ByteArrayOutputStream()
    val ps          = new PrintStream(baos)
    val originalSys = System.out
    implicit val rt: IORuntime = singleThreadedRuntime()

    System.setOut(ps)
    try {
      Console.withOut(ps) {
        RagLogger.logRetrieval[IO](userId = userId, tips = tips, notes = notes)
          .unsafeRunSync()
      }
      ps.flush()
    } finally {
      System.setOut(originalSys)
      rt.shutdown()
    }
    baos.toString
  }

  "RagLogger.logRetrieval" should {

    "produce a log line starting with RAG userId=42" in {
      val tips  = List(RetrievedTip(HabitTip(1L, "DISTINCTIVE_EXTERNAL_CONTENT_A"), 0.87))
      val notes = List(RetrievedTip(HabitTip(7L, "DISTINCTIVE_PERSONAL_CONTENT_B"), 0.91))

      val captured = captureLogRetrieval(42L, tips, notes)
      captured should startWith("RAG userId=42")
    }

    "include externalCount matching the seeded tips list size" in {
      val tips  = List(
        RetrievedTip(HabitTip(1L, "DISTINCTIVE_EXTERNAL_CONTENT_A"), 0.87),
        RetrievedTip(HabitTip(2L, "DISTINCTIVE_EXTERNAL_CONTENT_C"), 0.82)
      )
      val notes = List(RetrievedTip(HabitTip(7L, "DISTINCTIVE_PERSONAL_CONTENT_B"), 0.91))

      val captured = captureLogRetrieval(42L, tips, notes)
      captured should include("externalCount=2")
    }

    "include personalCount matching the seeded notes list size" in {
      val tips  = List(RetrievedTip(HabitTip(1L, "DISTINCTIVE_EXTERNAL_CONTENT_A"), 0.87))
      val notes = List(
        RetrievedTip(HabitTip(7L, "DISTINCTIVE_PERSONAL_CONTENT_B"), 0.91),
        RetrievedTip(HabitTip(8L, "DISTINCTIVE_PERSONAL_CONTENT_D"), 0.88)
      )

      val captured = captureLogRetrieval(42L, tips, notes)
      captured should include("personalCount=2")
    }

    "include topExternalScore and topPersonalScore as 2-decimal floats" in {
      val tips  = List(RetrievedTip(HabitTip(1L, "DISTINCTIVE_EXTERNAL_CONTENT_A"), 0.87))
      val notes = List(RetrievedTip(HabitTip(7L, "DISTINCTIVE_PERSONAL_CONTENT_B"), 0.91))

      val captured = captureLogRetrieval(42L, tips, notes)
      captured should include("topExternalScore=")
      captured should include("topPersonalScore=")
      captured should include regex "topExternalScore=\\d+\\.\\d{2}"
      captured should include regex "topPersonalScore=\\d+\\.\\d{2}"
    }

    "NOT include the content of any seeded RetrievedTip (privacy contract)" in {
      val externalContent = "DISTINCTIVE_EXTERNAL_CONTENT_XYZ123"
      val personalContent = "DISTINCTIVE_PERSONAL_CONTENT_ABC456"
      val tips  = List(RetrievedTip(HabitTip(1L, externalContent), 0.87))
      val notes = List(RetrievedTip(HabitTip(7L, personalContent), 0.91))

      val captured = captureLogRetrieval(42L, tips, notes)
      captured should not include externalContent
      captured should not include personalContent
    }
  }
}
