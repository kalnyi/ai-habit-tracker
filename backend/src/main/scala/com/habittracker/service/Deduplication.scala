package com.habittracker.service

import com.habittracker.model.RetrievedTip

/** Pure cross-source deduplication for the multi-source RAG pipeline.
  *
  * Two items (one from the curated `habit_tips` corpus, one from the
  * user's own `user_notes`) are considered duplicates if and only if:
  *   - their similarity scores differ by less than 0.05, AND
  *   - their word-overlap ratio exceeds 0.8.
  *
  * When a duplicate pair is found, the item with the higher score is
  * retained. On exact tie, the corpus tip is retained (deterministic).
  *
  * Pure: no F[_], no IO, no side effects. See ADR-011 §4. */
object Deduplication {

  private val SCORE_DIFF_THRESHOLD:   Double = 0.05
  private val WORD_OVERLAP_THRESHOLD: Double = 0.8

  /** Splits a string into a Set of lowercased word tokens. Whitespace
    * and punctuation are treated as separators. Empty tokens are dropped. */
  private def words(s: String): Set[String] =
    s.toLowerCase
      .split("\\W+")
      .iterator
      .filter(_.nonEmpty)
      .toSet

  /** Word-overlap ratio: |sharedWords| / max(|wordsA|, |wordsB|).
    * Returns 0.0 when both inputs have no words (no false-duplicate). */
  private[service] def wordOverlapRatio(a: String, b: String): Double = {
    val wa = words(a)
    val wb = words(b)
    val maxSize = math.max(wa.size, wb.size)
    if (maxSize == 0) 0.0
    else wa.intersect(wb).size.toDouble / maxSize.toDouble
  }

  /** A pair is a duplicate when both score-diff and word-overlap
    * conditions hold. */
  private[service] def isDuplicate(a: RetrievedTip, b: RetrievedTip): Boolean = {
    val scoreDiff = math.abs(a.similarityScore - b.similarityScore)
    val overlap   = wordOverlapRatio(a.tip.content, b.tip.content)
    scoreDiff < SCORE_DIFF_THRESHOLD && overlap > WORD_OVERLAP_THRESHOLD
  }

  /** Removes near-duplicates across the two sources. Items kept on each
    * side preserve their original relative order. */
  def deduplicate(
      tips:  List[RetrievedTip],
      notes: List[RetrievedTip]
  ): (List[RetrievedTip], List[RetrievedTip]) = {
    // For each (tip, note) duplicate pair, mark one side for removal.
    // The higher-scored item is kept; on tie the tip side is kept.
    val tipsIdx  = tips.zipWithIndex
    val notesIdx = notes.zipWithIndex

    val (tipDrops, noteDrops) = tipsIdx.foldLeft(
      (Set.empty[Int], Set.empty[Int])
    ) { case ((dropT, dropN), (t, ti)) =>
      notesIdx.foldLeft((dropT, dropN)) { case ((dt, dn), (n, ni)) =>
        if (dt.contains(ti) || dn.contains(ni)) (dt, dn)
        else if (isDuplicate(t, n)) {
          if (t.similarityScore >= n.similarityScore) (dt, dn + ni)
          else (dt + ti, dn)
        } else (dt, dn)
      }
    }

    val survivingTips  = tipsIdx.collect  { case (t, i) if !tipDrops(i)  => t }
    val survivingNotes = notesIdx.collect { case (n, i) if !noteDrops(i) => n }
    (survivingTips, survivingNotes)
  }
}
