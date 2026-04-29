package com.habittracker.repository

import cats.effect.IO
import com.habittracker.model.{HabitTip, RetrievedTip}
import doobie._
import doobie.implicits._

final class TipRepository(transactor: Transactor[IO]) {

  // -------------------------------------------------------------------------
  // pgvector wire-format helper
  //
  // pgvector accepts vector literals in the textual form `[v1,v2,...]`.
  // Doobie has no built-in Meta[Vector[Float]] (and we deliberately do not
  // pull in com.pgvector:pgvector-java per ADR-010 §5), so we render the
  // vector to its literal form and cast it back to `vector` in SQL via
  // `::vector`. This keeps the wire format explicit and reviewable.
  // -------------------------------------------------------------------------
  private def embeddingToPgLiteral(v: Vector[Float]): String =
    v.mkString("[", ",", "]")

  // -------------------------------------------------------------------------
  // SQL queries
  // -------------------------------------------------------------------------

  private def insertSql(content: String, embeddingLiteral: String): Update0 =
    sql"""
      INSERT INTO habit_tips (content, embedding)
      VALUES ($content, $embeddingLiteral::vector)
      RETURNING id
    """.update

  private def findByContentSql(content: String): Query0[(Long, String)] =
    sql"""
      SELECT id, content
      FROM habit_tips
      WHERE content = $content
      LIMIT 1
    """.query[(Long, String)]

  // Cosine similarity measures the angle between two embedding vectors.
  // A score of 1.0 means the vectors point in exactly the same direction
  // (identical meaning); 0.0 means they are orthogonal (unrelated meaning).
  // pgvector's `<=>` operator returns *cosine distance*, defined as
  // 1 - cosineSimilarity. We subtract from 1 to translate distance back
  // into the more intuitive similarity score before returning it to the
  // caller.
  private def similaritySearchSql(
      queryEmbedding: Vector[Float],
      topK:           Int
  ): Query0[(Long, String, Double)] = {
    val embeddingLiteral: String = embeddingToPgLiteral(queryEmbedding)
    sql"""
      SELECT id,
             content,
             1.0 - (embedding <=> $embeddingLiteral::vector) AS score
      FROM habit_tips
      ORDER BY embedding <=> $embeddingLiteral::vector
      LIMIT $topK
    """.query[(Long, String, Double)]
  }

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  def insert(content: String, embedding: Vector[Float]): IO[HabitTip] = {
    val literal = embeddingToPgLiteral(embedding)
    insertSql(content, literal)
      .withUniqueGeneratedKeys[Long]("id")
      .transact(transactor)
      .map(id => HabitTip(id, content))
  }

  def findExistingByContent(content: String): IO[Option[HabitTip]] =
    findByContentSql(content).option.transact(transactor).map {
      _.map { case (id, c) => HabitTip(id, c) }
    }

  // pgvector's `<=>` operator returns cosine *distance*, where smaller is
  // closer. Ordering ascending by `<=>` therefore returns rows from most
  // similar (smallest distance) to least similar (largest distance). The
  // caller sees results sorted by semantic similarity descending — which is
  // what RAG retrieval needs.
  def findSimilar(
      queryEmbedding: Vector[Float],
      topK:           Int
  ): IO[List[RetrievedTip]] =
    similaritySearchSql(queryEmbedding, topK)
      .to[List]
      .transact(transactor)
      .map(_.map { case (id, content, score) =>
        RetrievedTip(HabitTip(id, content), score)
      })
}
