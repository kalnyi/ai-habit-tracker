package com.habittracker.observability

import cats.effect.Async
import com.habittracker.model.RetrievedTip

/** Structured retrieval-quality logger for the multi-source RAG pipeline.
  *
  * Privacy contract (ADR-011 §5): this object MUST NOT log the `content`
  * field of any RetrievedTip. The corpus tips are non-sensitive but the
  * personal notes may contain PII (the user types them as free text).
  * The signal we want for observability is the score distribution and
  * the count — sufficient to detect empty results, low-confidence
  * retrieval, and source imbalance. */
object RagLogger {

  /** Logs the metadata of a single retrieval round to stdout.
    *
    * Format (single line):
    *   RAG userId=X externalCount=N personalCount=M
    *       topExternalScore=0.87 topPersonalScore=0.91
    */
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
