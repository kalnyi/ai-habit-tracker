package com.habittracker.service

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.{Clock, IO}
import cats.effect.testing.scalatest.AsyncIOSpec
import com.habittracker.TestClocks
import com.habittracker.domain.{Frequency, Habit}
import com.habittracker.domain.AppError.{NotFound, ValidationError}
import com.habittracker.http.dto.{CreateHabitRequest, UpdateHabitRequest}
import com.habittracker.repository.{HabitRepository, InMemoryHabitRepository}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.util.UUID

@RunWith(classOf[JUnitRunner])
class HabitServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  def makeService(
      repo: HabitRepository = new InMemoryHabitRepository(),
      clock: Clock[IO] = TestClocks.makeFakeClock(1_000_000_000_000L)
  ): HabitService =
    new DefaultHabitService(repo, clock)

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  "HabitService.createHabit" should {

    "return ValidationError when name is blank" in {
      val svc = makeService()
      svc.createHabit(1L, CreateHabitRequest("  ", None, "daily")).asserting { result =>
        result shouldBe Left(ValidationError("name must not be blank"))
      }
    }

    "return ValidationError when name is empty" in {
      val svc = makeService()
      svc.createHabit(1L, CreateHabitRequest("", None, "daily")).asserting { result =>
        result shouldBe Left(ValidationError("name must not be blank"))
      }
    }

    "return ValidationError when frequency is unknown" in {
      val svc = makeService()
      svc.createHabit(1L, CreateHabitRequest("Run", None, "monthly")).asserting { result =>
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[ValidationError]
      }
    }

    "create a habit with valid input and set id, createdAt, updatedAt" in {
      val svc = makeService()
      svc.createHabit(1L, CreateHabitRequest("Read 20 pages", Some("Non-fiction"), "daily")).asserting {
        result =>
          result.isRight shouldBe true
          val habit = result.toOption.get
          habit.id should not be null
          habit.name shouldBe "Read 20 pages"
          habit.description shouldBe Some("Non-fiction")
          habit.frequency shouldBe "daily"
          habit.createdAt should not be null
          habit.updatedAt should not be null
      }
    }

    "create a habit with description None when not provided" in {
      val svc = makeService()
      svc.createHabit(1L, CreateHabitRequest("Exercise", None, "weekly")).asserting { result =>
        result.isRight shouldBe true
        result.toOption.get.description shouldBe None
      }
    }
  }

  "HabitService.updateHabit" should {

    "return NotFound when habit does not exist" in {
      val svc = makeService()
      svc.updateHabit(1L, UUID.randomUUID(), UpdateHabitRequest("New name", None, "daily")).asserting {
        result =>
          result.isLeft shouldBe true
          result.swap.toOption.get shouldBe a[NotFound]
      }
    }

    "set updatedAt strictly later than createdAt" in {
      val repo  = new InMemoryHabitRepository()
      val clock = TestClocks.makeFakeClock(1_000_000_000_000L)
      val svc   = makeService(repo, clock)

      for {
        created <- svc.createHabit(1L, CreateHabitRequest("Run", None, "daily"))
        id = created.toOption.get.id
        original = created.toOption.get
        updated <- svc.updateHabit(1L, id, UpdateHabitRequest("Walk", None, "weekly"))
      } yield {
        updated.isRight shouldBe true
        val u = updated.toOption.get
        u.name shouldBe "Walk"
        u.updatedAt.isAfter(original.createdAt) shouldBe true
      }
    }

    "return ValidationError when name is blank on update" in {
      val repo = new InMemoryHabitRepository()
      val svc  = makeService(repo)
      for {
        _   <- svc.createHabit(1L, CreateHabitRequest("Run", None, "daily"))
        ids  = repo.store.keys.toList
        id   = ids.head
        res <- svc.updateHabit(1L, id, UpdateHabitRequest("", None, "daily"))
      } yield {
        res shouldBe Left(ValidationError("name must not be blank"))
      }
    }

    "return NotFound when the habit belongs to a different user" in {
      val repo = new InMemoryHabitRepository()
      val svc  = makeService(repo)
      val now  = Instant.now()
      val habit = Habit(UUID.randomUUID(), 2L, "Other user habit", None, Frequency.Daily, now, now, None)
      repo.store.put(habit.id, habit)
      svc.updateHabit(1L, habit.id, UpdateHabitRequest("New name", None, "daily")).asserting { result =>
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[NotFound]
      }
    }
  }

  "HabitService.deleteHabit" should {

    "return NotFound when deleting a non-existent habit" in {
      val svc = makeService()
      svc.deleteHabit(1L, UUID.randomUUID()).asserting { result =>
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[NotFound]
      }
    }

    "succeed on first delete and return NotFound on second delete" in {
      val svc = makeService()
      for {
        created <- svc.createHabit(1L, CreateHabitRequest("Meditate", None, "daily"))
        id = created.toOption.get.id
        first  <- svc.deleteHabit(1L, id)
        second <- svc.deleteHabit(1L, id)
      } yield {
        first shouldBe Right(())
        second.isLeft shouldBe true
        second.swap.toOption.get shouldBe a[NotFound]
      }
    }

    "return NotFound when the habit belongs to a different user" in {
      val repo = new InMemoryHabitRepository()
      val svc  = makeService(repo)
      val now  = Instant.now()
      val habit = Habit(UUID.randomUUID(), 2L, "Other user habit", None, Frequency.Daily, now, now, None)
      repo.store.put(habit.id, habit)
      svc.deleteHabit(1L, habit.id).asserting { result =>
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[NotFound]
      }
    }
  }

  "HabitService.listHabits" should {

    "return empty list when no habits exist" in {
      val svc = makeService()
      svc.listHabits(1L).asserting { result =>
        result shouldBe Right(List.empty)
      }
    }

    "exclude soft-deleted habits" in {
      val svc = makeService()
      for {
        _  <- svc.createHabit(1L, CreateHabitRequest("Active habit", None, "daily"))
        h2 <- svc.createHabit(1L, CreateHabitRequest("To be deleted", None, "weekly"))
        id = h2.toOption.get.id
        _ <- svc.deleteHabit(1L, id)
        list <- svc.listHabits(1L)
      } yield {
        list.isRight shouldBe true
        val habits = list.toOption.get
        habits.length shouldBe 1
        habits.head.name shouldBe "Active habit"
      }
    }
  }

  "HabitService.getHabit" should {

    "return NotFound when the habit belongs to a different user" in {
      val repo = new InMemoryHabitRepository()
      val svc  = makeService(repo)
      val now  = Instant.now()
      val habit = Habit(UUID.randomUUID(), 2L, "Other user habit", None, Frequency.Daily, now, now, None)
      repo.store.put(habit.id, habit)
      svc.getHabit(1L, habit.id).asserting { result =>
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[NotFound]
      }
    }
  }
}
