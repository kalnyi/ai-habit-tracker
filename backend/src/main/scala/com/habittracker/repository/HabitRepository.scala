package com.habittracker.repository

import cats.effect.IO
import com.habittracker.domain.{Frequency, Habit}

import java.time.Instant
import java.util.UUID

trait HabitRepository {

  def create(habit: Habit): IO[Unit]

  def listActive(): IO[List[Habit]]

  def findActiveById(id: UUID): IO[Option[Habit]]

  def updateActive(
      id: UUID,
      name: String,
      description: Option[String],
      frequency: Frequency,
      updatedAt: Instant
  ): IO[Option[Habit]]

  def softDelete(id: UUID, at: Instant): IO[Boolean]
}
