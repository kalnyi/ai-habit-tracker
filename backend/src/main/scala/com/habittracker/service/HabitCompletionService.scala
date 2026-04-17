package com.habittracker.service

import cats.effect.{Clock, IO}
import com.habittracker.domain.AppError
import com.habittracker.domain.AppError.NotFound
import com.habittracker.domain.HabitCompletion
import com.habittracker.http.dto.{CreateHabitCompletionRequest, HabitCompletionResponse}
import com.habittracker.repository.{HabitCompletionRepository, HabitRepository}

import java.time.LocalDate
import java.util.UUID

trait HabitCompletionService {

  def recordCompletion(
      habitId: UUID,
      req: CreateHabitCompletionRequest
  ): IO[Either[AppError, HabitCompletionResponse]]

  def listCompletions(
      habitId: UUID,
      from: Option[LocalDate],
      to: Option[LocalDate]
  ): IO[Either[AppError, List[HabitCompletionResponse]]]

  def deleteCompletion(
      habitId: UUID,
      completionId: UUID
  ): IO[Either[AppError, Unit]]
}

final class DefaultHabitCompletionService(
    habitRepo: HabitRepository,
    completionRepo: HabitCompletionRepository,
    clock: Clock[IO]
) extends HabitCompletionService {

  override def recordCompletion(
      habitId: UUID,
      req: CreateHabitCompletionRequest
  ): IO[Either[AppError, HabitCompletionResponse]] =
    habitRepo.findActiveById(habitId).flatMap {
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
            createdAt = now
          )
          result <- completionRepo.create(completion)
        } yield result match {
          case Left(err)  => Left(err): Either[AppError, HabitCompletionResponse]
          case Right(()) => Right(HabitCompletionResponse.fromHabitCompletion(completion))
        }
    }

  override def listCompletions(
      habitId: UUID,
      from: Option[LocalDate],
      to: Option[LocalDate]
  ): IO[Either[AppError, List[HabitCompletionResponse]]] =
    habitRepo.findActiveById(habitId).flatMap {
      case None =>
        IO.pure(Left(NotFound(s"Habit '$habitId' not found")))
      case Some(_) =>
        completionRepo
          .listByHabit(habitId, from, to)
          .map(cs => Right(cs.map(HabitCompletionResponse.fromHabitCompletion)))
    }

  override def deleteCompletion(
      habitId: UUID,
      completionId: UUID
  ): IO[Either[AppError, Unit]] =
    habitRepo.findActiveById(habitId).flatMap {
      case None =>
        IO.pure(Left(NotFound(s"Habit '$habitId' not found")))
      case Some(_) =>
        completionRepo.deleteByIdAndHabit(completionId, habitId).map {
          case true  => Right(())
          case false => Left(NotFound(s"Completion '$completionId' not found"))
        }
    }
}
