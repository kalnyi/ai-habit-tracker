package com.habittracker.service

import cats.effect.{Clock, IO}
import cats.syntax.all._
import com.habittracker.domain.AppError
import com.habittracker.domain.AppError.{ConflictError, NotFound}
import com.habittracker.domain.HabitCompletion
import com.habittracker.http.dto.{BatchCompletionItem, BatchCompletionResponse, CreateHabitCompletionRequest, HabitCompletionResponse, SkippedCompletion}
import com.habittracker.repository.{HabitCompletionRepository, HabitRepository}

import java.time.LocalDate
import java.util.UUID

trait HabitCompletionService {

  def recordCompletion(
      userId: Long,
      habitId: UUID,
      req: CreateHabitCompletionRequest
  ): IO[Either[AppError, HabitCompletionResponse]]

  def listCompletions(
      userId: Long,
      habitId: UUID,
      from: Option[LocalDate],
      to: Option[LocalDate]
  ): IO[Either[AppError, List[HabitCompletionResponse]]]

  def deleteCompletion(
      userId: Long,
      habitId: UUID,
      completionId: UUID
  ): IO[Either[AppError, Unit]]

  /** Processes each item independently; returns inserted and skipped lists.
    * Always returns IO[BatchCompletionResponse] — no whole-batch failure mode.
    * See ADR-010 §9.
    *
    * Default implementation raises an error to keep Phase 1/2 fake services
    * (which extend this trait) compiling without modification. */
  def recordCompletionBatch(
      userId: Long,
      items:  List[BatchCompletionItem]
  ): IO[BatchCompletionResponse] =
    IO.raiseError(new NotImplementedError("recordCompletionBatch not implemented in this service"))
}

final class DefaultHabitCompletionService(
    habitRepo: HabitRepository,
    completionRepo: HabitCompletionRepository,
    clock: Clock[IO]
) extends HabitCompletionService {

  override def recordCompletion(
      userId: Long,
      habitId: UUID,
      req: CreateHabitCompletionRequest
  ): IO[Either[AppError, HabitCompletionResponse]] =
    habitRepo.findActiveById(userId, habitId).flatMap {
      case None =>
        IO.pure(Left(NotFound(s"Habit '$habitId' not found")))
      case Some(_) =>
        for {
          now <- clock.realTimeInstant
          id  <- IO(UUID.randomUUID())
          completion = HabitCompletion(
            id = id,
            habitId = habitId,
            completedOn = req.completedOn,
            note = req.note,
            createdAt = now,
            completedAt = req.completedAt
          )
          result <- completionRepo.create(completion)
        } yield result match {
          case Left(err)  => Left(err): Either[AppError, HabitCompletionResponse]
          case Right(()) => Right(HabitCompletionResponse.fromHabitCompletion(completion))
        }
    }

  override def listCompletions(
      userId: Long,
      habitId: UUID,
      from: Option[LocalDate],
      to: Option[LocalDate]
  ): IO[Either[AppError, List[HabitCompletionResponse]]] =
    habitRepo.findActiveById(userId, habitId).flatMap {
      case None =>
        IO.pure(Left(NotFound(s"Habit '$habitId' not found")))
      case Some(_) =>
        completionRepo
          .listByHabit(habitId, from, to)
          .map(cs => Right(cs.map(HabitCompletionResponse.fromHabitCompletion)))
    }

  override def deleteCompletion(
      userId: Long,
      habitId: UUID,
      completionId: UUID
  ): IO[Either[AppError, Unit]] =
    habitRepo.findActiveById(userId, habitId).flatMap {
      case None =>
        IO.pure(Left(NotFound(s"Habit '$habitId' not found")))
      case Some(_) =>
        completionRepo.deleteByIdAndHabit(completionId, habitId).map {
          case true  => Right(())
          case false => Left(NotFound(s"Completion '$completionId' not found"))
        }
    }

  override def recordCompletionBatch(
      userId: Long,
      items:  List[BatchCompletionItem]
  ): IO[BatchCompletionResponse] =
    items.traverse(processOne(userId, _)).map { results =>
      val inserted = results.collect { case Right(r) => r }
      val skipped  = results.collect { case Left(s)  => s }
      BatchCompletionResponse(inserted, skipped)
    }

  private def processOne(
      userId: Long,
      item:   BatchCompletionItem
  ): IO[Either[SkippedCompletion, HabitCompletionResponse]] =
    habitRepo.findActiveById(userId, item.habitId).flatMap {
      case None =>
        IO.pure(Left(SkippedCompletion(
          habitId     = item.habitId,
          completedOn = item.completedOn,
          reason      = "habit not found or not active for this user"
        )))
      case Some(_) =>
        for {
          now <- clock.realTimeInstant
          id  <- IO(UUID.randomUUID())
          completion = HabitCompletion(
            id          = id,
            habitId     = item.habitId,
            completedOn = item.completedOn,
            note        = item.note,
            createdAt   = now,
            completedAt = item.completedAt
          )
          result <- completionRepo.create(completion)
        } yield result.fold(
          { case ConflictError(msg) =>
            Left(SkippedCompletion(
              habitId     = item.habitId,
              completedOn = item.completedOn,
              reason      = s"duplicate: $msg"
            ))
          },
          { _ => Right(HabitCompletionResponse.fromHabitCompletion(completion)) }
        )
    }
}
