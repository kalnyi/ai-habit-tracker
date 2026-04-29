package com.habittracker.repository

import cats.effect.IO
import com.habittracker.model.{HabitTip, RetrievedTip, UserNote}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.time.Instant

final class NoteRepository(transactor: Transactor[IO]) {

  // -------------------------------------------------------------------------
  // pgvector wire-format helper — same approach as TipRepository.
  // pgvector accepts vector literals in the textual form `[v1,v2,...]`.
  // We render the Vector[Float] to that literal form and cast it back to
  // `vector` in SQL via `::vector`. This keeps the wire format explicit
  // and reviewable, with no dependency on com.pgvector:pgvector-java.
  // See ADR-010 §5 for the rationale.
  // -------------------------------------------------------------------------
  private def embeddingToPgLiteral(v: Vector[Float]): String =
    v.mkString("[", ",", "]")

  // -------------------------------------------------------------------------
  // SQL queries
  // -------------------------------------------------------------------------

  private def insertSql(
      userId:           Long,
      content:          String,
      embeddingLiteral: String
  ): Query0[(Long, Instant)] =
    sql"""
      INSERT INTO user_notes (user_id, content, embedding)
      VALUES ($userId, $content, $embeddingLiteral::vector)
      RETURNING id, created_at
    """.query[(Long, Instant)]

  // Cosine similarity over the user's own notes — same operator semantics
  // as TipRepository.similaritySearchSql. The WHERE user_id = $userId
  // clause enforces user-scoping at the database level: a query for user A
  // must never return notes belonging to user B (ADR-007/008, ADR-011 §1).
  // pgvector's `<=>` operator returns cosine distance; we subtract from 1
  // to translate it into the more intuitive similarity score.
  val similaritySearchSql: String =
    "SELECT id, content, 1.0 - (embedding <=> ?::vector) AS score " +
    "FROM user_notes WHERE user_id = ? " +
    "ORDER BY embedding <=> ?::vector LIMIT ?"

  private def similaritySearchQuery(
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

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  def insert(
      userId:    Long,
      content:   String,
      embedding: Vector[Float]
  ): IO[UserNote] = {
    val literal = embeddingToPgLiteral(embedding)
    insertSql(userId, content, literal)
      .unique
      .transact(transactor)
      .map { case (id, createdAt) =>
        UserNote(id = id, userId = userId, content = content, createdAt = createdAt)
      }
  }

  // pgvector's `<=>` operator returns cosine *distance*, where smaller is
  // closer. Ordering ascending by `<=>` returns rows from most similar to
  // least similar — i.e. the caller sees results sorted by semantic
  // similarity descending, which is what RAG retrieval needs.
  def findSimilar(
      userId:         Long,
      queryEmbedding: Vector[Float],
      topK:           Int
  ): IO[List[RetrievedTip]] =
    similaritySearchQuery(userId, queryEmbedding, topK)
      .to[List]
      .transact(transactor)
      .map(_.map { case (id, content, score) =>
        RetrievedTip(HabitTip(id, content), score)
      })
}
