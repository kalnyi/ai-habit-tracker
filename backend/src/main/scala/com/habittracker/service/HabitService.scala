package com.habittracker.service

import cats.effect.{Clock, IO}
import com.habittracker.domain.AppError.{NotFound, ValidationError}
import com.habittracker.domain.{AppError, Frequency, Habit}
import com.habittracker.http.dto.{CreateHabitRequest, HabitResponse, UpdateHabitRequest}
import com.habittracker.repository.HabitRepository

import java.util.UUID

trait HabitService {

  def createHabit(req: CreateHabitRequest): IO[Either[AppError, HabitResponse]]

  def listHabits(): IO[Either[AppError, List[HabitResponse]]]

  def getHabit(id: UUID): IO[Either[AppError, HabitResponse]]

  def updateHabit(id: UUID, req: UpdateHabitRequest): IO[Either[AppError, HabitResponse]]

  def deleteHabit(id: UUID): IO[Either[AppError, Unit]]
}

final class DefaultHabitService(
    repo: HabitRepository,
    clock: Clock[IO]
) extends HabitService {

  override def createHabit(req: CreateHabitRequest): IO[Either[AppError, HabitResponse]] = {
    val validationResult: Either[AppError, Frequency] = for {
      _ <- validateName(req.name)
      freq <- Frequency.parse(req.frequency).left.map(msg => ValidationError(msg): AppError)
    } yield freq

    validationResult match {
      case Left(err) => IO.pure(Left(err))
      case Right(freq) =>
        for {
          now <- clock.realTimeInstant
          id = UUID.randomUUID()
          habit = Habit(
            id = id,
            name = req.name.trim,
            description = req.description.map(_.trim).filter(_.nonEmpty),
            frequency = freq,
            createdAt = now,
            updatedAt = now,
            deletedAt = None
          )
          _ <- repo.create(habit)
        } yield Right(HabitResponse.fromHabit(habit))
    }
  }

  override def listHabits(): IO[Either[AppError, List[HabitResponse]]] =
    repo.listActive().map(habits => Right(habits.map(HabitResponse.fromHabit)))

  override def getHabit(id: UUID): IO[Either[AppError, HabitResponse]] =
    repo.findActiveById(id).map {
      case Some(h) => Right(HabitResponse.fromHabit(h))
      case None    => Left(NotFound(s"Habit with id '$id' not found"))
    }

  override def updateHabit(id: UUID, req: UpdateHabitRequest): IO[Either[AppError, HabitResponse]] = {
    val validationResult: Either[AppError, Frequency] = for {
      _ <- validateName(req.name)
      freq <- Frequency.parse(req.frequency).left.map(msg => ValidationError(msg): AppError)
    } yield freq

    validationResult match {
      case Left(err) => IO.pure(Left(err))
      case Right(freq) =>
        clock.realTimeInstant.flatMap { now =>
          repo
            .updateActive(
              id,
              req.name.trim,
              req.description.map(_.trim).filter(_.nonEmpty),
              freq,
              now
            )
            .map {
              case Some(h) => Right(HabitResponse.fromHabit(h))
              case None    => Left(NotFound(s"Habit with id '$id' not found"))
            }
        }
    }
  }

  override def deleteHabit(id: UUID): IO[Either[AppError, Unit]] =
    clock.realTimeInstant.flatMap { now =>
      repo.softDelete(id, now).map {
        case true  => Right(())
        case false => Left(NotFound(s"Habit with id '$id' not found"))
      }
    }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private def validateName(name: String): Either[AppError, String] =
    if (name == null || name.trim.isEmpty)
      Left(ValidationError("name must not be blank"))
    else
      Right(name.trim)
}
