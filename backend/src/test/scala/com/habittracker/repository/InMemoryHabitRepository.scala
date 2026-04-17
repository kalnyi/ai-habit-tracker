package com.habittracker.repository

import cats.effect.IO
import com.habittracker.domain.{Frequency, Habit}

import java.time.Instant
import java.util.UUID
import scala.collection.mutable

/** In-memory test double for HabitRepository.
  *
  * Lifted from HabitServiceSpec so it can be shared by HabitCompletionServiceSpec
  * and any other test that needs a fast, Docker-free habit store.
  */
class InMemoryHabitRepository extends HabitRepository {
  val store: mutable.Map[UUID, Habit] = mutable.LinkedHashMap.empty

  override def create(habit: Habit): IO[Unit] =
    IO { store.put(habit.id, habit); () }

  override def listActive(): IO[List[Habit]] =
    IO { store.values.filter(_.deletedAt.isEmpty).toList }

  override def findActiveById(id: UUID): IO[Option[Habit]] =
    IO { store.get(id).filter(_.deletedAt.isEmpty) }

  override def updateActive(
      id: UUID,
      name: String,
      description: Option[String],
      frequency: Frequency,
      updatedAt: Instant
  ): IO[Option[Habit]] =
    IO {
      store.get(id).filter(_.deletedAt.isEmpty).map { existing =>
        val updated = existing.copy(
          name = name,
          description = description,
          frequency = frequency,
          updatedAt = updatedAt
        )
        store.put(id, updated)
        updated
      }
    }

  override def softDelete(id: UUID, at: Instant): IO[Boolean] =
    IO {
      store.get(id).filter(_.deletedAt.isEmpty) match {
        case Some(h) =>
          store.put(id, h.copy(deletedAt = Some(at), updatedAt = at))
          true
        case None => false
      }
    }
}
