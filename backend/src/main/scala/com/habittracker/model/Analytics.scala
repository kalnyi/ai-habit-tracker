package com.habittracker.model

import java.time.Instant
import java.util.UUID

final case class HabitContext(
    userId:             Long,
    streaks:            Map[UUID, Int],
    completionByDay:    Map[String, Double],
    consistencyRanking: List[(String, Double)],
    timeOfDayPatterns:  Map[String, Double]              = Map.empty,
    correlatedPairs:    List[(String, String, Double)]   = Nil,
    momentumScores:     Map[UUID, Double]                = Map.empty,
    retrievedTips:      List[String]                     = Nil   // Phase 3: default Nil keeps frozen Phase 1/2 tests compiling
)

final case class InsightResponse(
    analytics: HabitContext,
    narrative: String
)

final case class AnalysisResponse(
    analytics: HabitContext,
    narrative: String
)

// ---------------------------------------------------------------------------
// Phase 3: RAG corpus types
// ---------------------------------------------------------------------------

/** A single tip stored in the habit_tips corpus table. */
final case class HabitTip(id: Long, content: String)

/** A tip retrieved from the corpus, paired with its cosine similarity score
  * relative to the user's query embedding. */
final case class RetrievedTip(tip: HabitTip, similarityScore: Double)

// ---------------------------------------------------------------------------
// Phase 4: user-notes RAG source
// ---------------------------------------------------------------------------

/** A user-authored note persisted in the user_notes table and returned
  * by POST /users/{userId}/habits/notes. */
final case class UserNote(
    id:        Long,
    userId:    Long,
    content:   String,
    createdAt: Instant
)

/** Request body for POST /users/{userId}/habits/notes. */
final case class NoteRequest(content: String)

// ---------------------------------------------------------------------------
// TipsResponse — Phase 4 BREAKING CHANGE
// ---------------------------------------------------------------------------

/** Response returned by GET /users/{userId}/habits/tips.
  *
  * Phase 4 breaking change: the single `tips` field is replaced by
  * `externalTips` (Phase 3 corpus source) and `personalNotes` (Phase 4
  * user-notes source). See ADR-011 §6. */
final case class TipsResponse(
    externalTips:  List[RetrievedTip],
    personalNotes: List[RetrievedTip],
    narrative:     String
)
