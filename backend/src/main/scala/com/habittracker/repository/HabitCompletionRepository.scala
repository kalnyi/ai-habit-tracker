package com.habittracker.repository

import cats.effect.IO
import com.habittracker.domain.AppError.ConflictError
import com.habittracker.domain.HabitCompletion

import java.time.LocalDate
import java.util.UUID

trait HabitCompletionRepository {

  def create(completion: HabitCompletion): IO[Either[ConflictError, Unit]]

  def findByHabitAndDate(habitId: UUID, completedOn: LocalDate): IO[Option[HabitCompletion]]

  def listByHabit(
      habitId: UUID,
      from: Option[LocalDate],
      to: Option[LocalDate]
  ): IO[List[HabitCompletion]]

  def deleteByIdAndHabit(completionId: UUID, habitId: UUID): IO[Boolean]
}
