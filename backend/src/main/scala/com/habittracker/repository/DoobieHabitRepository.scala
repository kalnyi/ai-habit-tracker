package com.habittracker.repository

import cats.effect.IO
import com.habittracker.domain.{Frequency, Habit}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

/** Doobie implementation of HabitRepository.
  *
  * Uses `doobie.postgres.implicits._` to bring in the PostgreSQL-native
  * java.time Meta instances (OffsetDateTime -> Instant via JavaTimeInstances).
  * All SQL parameters are bound via Fragment interpolation; no raw strings.
  */
final class DoobieHabitRepository(transactor: Transactor[IO]) extends HabitRepository {

  // ---------------------------------------------------------------------------
  // Meta instance for Frequency ADT (stored as VARCHAR)
  // ---------------------------------------------------------------------------

  private implicit val frequencyMeta: Meta[Frequency] =
    Meta[String].timap(
      s => Frequency.parse(s).getOrElse(Frequency.Custom(s))
    )(Frequency.asString)
    

  // ---------------------------------------------------------------------------
  // Explicit Read[Habit] — UUID, Long (user_id), String, Option[String],
  // Frequency (via Meta above), Instant (from doobie.postgres.implicits),
  // Instant, Option[Instant]
  // ---------------------------------------------------------------------------

  private implicit val habitRead: Read[Habit] =
    Read[(UUID, Long, String, Option[String], Frequency, Instant, Instant, Option[Instant])].map {
      case (id, userId, name, desc, freq, createdAt, updatedAt, deletedAt) =>
        Habit(id, userId, name, desc, freq, createdAt, updatedAt, deletedAt)
    }

  // ---------------------------------------------------------------------------
  // SQL queries
  // ---------------------------------------------------------------------------

  private def insertQuery(h: Habit): Update0 = {
    val freqStr = Frequency.asString(h.frequency)
    sql"""
      INSERT INTO habits (id, user_id, name, description, frequency, created_at, updated_at, deleted_at)
      VALUES (${h.id}, ${h.userId}, ${h.name}, ${h.description}, $freqStr, ${h.createdAt}, ${h.updatedAt}, ${h.deletedAt})
    """.update
  }

  private def listActiveQuery(userId: Long): Query0[Habit] =
    sql"""
      SELECT id, user_id, name, description, frequency, created_at, updated_at, deleted_at
      FROM habits
      WHERE user_id = $userId AND deleted_at IS NULL
      ORDER BY created_at DESC
    """.query[Habit]

  private def findActiveByIdQuery(userId: Long, id: UUID): Query0[Habit] =
    sql"""
      SELECT id, user_id, name, description, frequency, created_at, updated_at, deleted_at
      FROM habits
      WHERE id = $id AND user_id = $userId AND deleted_at IS NULL
    """.query[Habit]

  private def updateActiveQuery(
      userId: Long,
      id: UUID,
      name: String,
      description: Option[String],
      frequencyStr: String,
      updatedAt: Instant
  ): Query0[Habit] =
    sql"""
      UPDATE habits
      SET name = $name,
          description = $description,
          frequency = $frequencyStr,
          updated_at = $updatedAt
      WHERE id = $id AND user_id = $userId AND deleted_at IS NULL
      RETURNING id, user_id, name, description, frequency, created_at, updated_at, deleted_at
    """.query[Habit]

  private def softDeleteQuery(userId: Long, id: UUID, at: Instant): Update0 =
    sql"""
      UPDATE habits
      SET deleted_at = $at,
          updated_at = $at
      WHERE id = $id AND user_id = $userId AND deleted_at IS NULL
    """.update

  // ---------------------------------------------------------------------------
  // HabitRepository implementation
  // ---------------------------------------------------------------------------

  override def create(habit: Habit): IO[Unit] =
    insertQuery(habit).run.transact(transactor).void

  override def listActive(userId: Long): IO[List[Habit]] =
    listActiveQuery(userId).to[List].transact(transactor)

  override def findActiveById(userId: Long, id: UUID): IO[Option[Habit]] =
    findActiveByIdQuery(userId, id).option.transact(transactor)

  override def updateActive(
      userId: Long,
      id: UUID,
      name: String,
      description: Option[String],
      frequency: Frequency,
      updatedAt: Instant
  ): IO[Option[Habit]] =
    updateActiveQuery(userId, id, name, description, Frequency.asString(frequency), updatedAt)
      .option
      .transact(transactor)

  override def softDelete(userId: Long, id: UUID, at: Instant): IO[Boolean] =
    softDeleteQuery(userId, id, at).run.transact(transactor).map(_ > 0)
}
