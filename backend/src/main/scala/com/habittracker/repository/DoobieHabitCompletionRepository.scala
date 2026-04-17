package com.habittracker.repository

import cats.effect.IO
import com.habittracker.domain.AppError.ConflictError
import com.habittracker.domain.HabitCompletion
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.sqlstate
import doobie.util.catchsql

import java.time.{Instant, LocalDate}
import java.util.UUID

/** Doobie implementation of HabitCompletionRepository.
  *
  * Uses `doobie.postgres.implicits._` to bring in the PostgreSQL-native
  * java.time Meta instances (Meta[LocalDate] for DATE, Meta[Instant] for
  * TIMESTAMPTZ). All SQL parameters are bound via Fragment interpolation.
  *
  * `create` uses `catchsql.attemptSomeSqlState` to catch the PostgreSQL
  * UNIQUE_VIOLATION (23505) and translate it into a typed `Left(ConflictError)`.
  * All other SQLExceptions propagate as IO failures.
  */
final class DoobieHabitCompletionRepository(transactor: Transactor[IO])
    extends HabitCompletionRepository {

  // ---------------------------------------------------------------------------
  // Explicit Read[HabitCompletion]
  // ---------------------------------------------------------------------------

  private implicit val completionRead: Read[HabitCompletion] =
    Read[(UUID, UUID, LocalDate, Option[String], Instant)].map {
      case (id, habitId, completedOn, note, createdAt) =>
        HabitCompletion(id, habitId, completedOn, note, createdAt)
    }

  // ---------------------------------------------------------------------------
  // SQL fragments
  // ---------------------------------------------------------------------------

  private def insertQuery(c: HabitCompletion): Update0 =
    sql"""
      INSERT INTO habit_completions (id, habit_id, completed_on, note, created_at)
      VALUES (${c.id}, ${c.habitId}, ${c.completedOn}, ${c.note}, ${c.createdAt})
    """.update

  private def findByHabitAndDateQuery(habitId: UUID, completedOn: LocalDate): Query0[HabitCompletion] =
    sql"""
      SELECT id, habit_id, completed_on, note, created_at
      FROM habit_completions
      WHERE habit_id = $habitId AND completed_on = $completedOn
    """.query[HabitCompletion]

  private def listByHabitQuery(
      habitId: UUID,
      from: Option[LocalDate],
      to: Option[LocalDate]
  ): Query0[HabitCompletion] = {
    val base     = fr"""
      SELECT id, habit_id, completed_on, note, created_at
      FROM habit_completions
      WHERE habit_id = $habitId
    """
    val fromFrag = from.map(f => fr"AND completed_on >= $f").getOrElse(Fragment.empty)
    val toFrag   = to.map(t => fr"AND completed_on <= $t").getOrElse(Fragment.empty)
    val order    = fr"ORDER BY completed_on DESC"
    (base ++ fromFrag ++ toFrag ++ order).query[HabitCompletion]
  }

  private def deleteByIdAndHabitQuery(completionId: UUID, habitId: UUID): Update0 =
    sql"""
      DELETE FROM habit_completions
      WHERE id = $completionId AND habit_id = $habitId
    """.update

  // ---------------------------------------------------------------------------
  // HabitCompletionRepository implementation
  // ---------------------------------------------------------------------------

  override def create(completion: HabitCompletion): IO[Either[ConflictError, Unit]] = {
    // `attemptSomeSqlState` catches only the UNIQUE_VIOLATION SqlState and maps
    // it to ConflictError; all other SQLExceptions propagate as IO failures.
    val insertIO: IO[Int] = insertQuery(completion).run.transact(transactor)
    catchsql
      .attemptSomeSqlState[IO, Int, ConflictError](insertIO) {
        case sqlstate.class23.UNIQUE_VIOLATION =>
          ConflictError(
            s"Habit '${completion.habitId}' already has a completion for ${completion.completedOn}"
          )
      }
      .map {
        case Left(err) => Left(err)
        case Right(_)  => Right(())
      }
  }

  override def findByHabitAndDate(
      habitId: UUID,
      completedOn: LocalDate
  ): IO[Option[HabitCompletion]] =
    findByHabitAndDateQuery(habitId, completedOn).option.transact(transactor)

  override def listByHabit(
      habitId: UUID,
      from: Option[LocalDate],
      to: Option[LocalDate]
  ): IO[List[HabitCompletion]] =
    listByHabitQuery(habitId, from, to).to[List].transact(transactor)

  override def deleteByIdAndHabit(completionId: UUID, habitId: UUID): IO[Boolean] =
    deleteByIdAndHabitQuery(completionId, habitId).run.transact(transactor).map(_ > 0)
}
