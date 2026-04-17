package com.habittracker.service

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.{Clock, IO}
import cats.effect.testing.scalatest.AsyncIOSpec
import com.habittracker.TestClocks
import com.habittracker.domain.AppError.{ConflictError, NotFound}
import com.habittracker.domain.{Frequency, Habit, HabitCompletion}
import com.habittracker.http.dto.CreateHabitCompletionRequest
import com.habittracker.repository.{HabitCompletionRepository, InMemoryHabitRepository}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.collection.mutable

@RunWith(classOf[JUnitRunner])
class HabitCompletionServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  // ---------------------------------------------------------------------------
  // In-memory completion repository double
  // ---------------------------------------------------------------------------

  class InMemoryHabitCompletionRepository extends HabitCompletionRepository {
    val store: mutable.Map[UUID, HabitCompletion] = mutable.LinkedHashMap.empty

    override def create(
        completion: HabitCompletion
    ): IO[Either[com.habittracker.domain.AppError.ConflictError, Unit]] =
      IO {
        val duplicate = store.values.exists(c =>
          c.habitId == completion.habitId && c.completedOn == completion.completedOn
        )
        if (duplicate) Left(ConflictError(s"Duplicate completion"))
        else {
          store.put(completion.id, completion)
          Right(())
        }
      }

    override def findByHabitAndDate(
        habitId: UUID,
        completedOn: LocalDate
    ): IO[Option[HabitCompletion]] =
      IO {
        store.values.find(c => c.habitId == habitId && c.completedOn == completedOn)
      }

    override def listByHabit(
        habitId: UUID,
        from: Option[LocalDate],
        to: Option[LocalDate]
    ): IO[List[HabitCompletion]] =
      IO {
        store.values
          .filter(_.habitId == habitId)
          .filter(c => from.forall(f => !c.completedOn.isBefore(f)))
          .filter(c => to.forall(t => !c.completedOn.isAfter(t)))
          .toList
          .sortBy(_.completedOn)(Ordering[LocalDate].reverse)
      }

    override def deleteByIdAndHabit(completionId: UUID, habitId: UUID): IO[Boolean] =
      IO {
        store.get(completionId) match {
          case Some(c) if c.habitId == habitId =>
            store.remove(completionId)
            true
          case _ => false
        }
      }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  def makeHabit(deletedAt: Option[Instant] = None): Habit = {
    val now = Instant.now()
    Habit(UUID.randomUUID(), "Test habit", None, Frequency.Daily, now, now, deletedAt)
  }

  def makeService(
      habitRepo: InMemoryHabitRepository = new InMemoryHabitRepository(),
      completionRepo: InMemoryHabitCompletionRepository = new InMemoryHabitCompletionRepository(),
      clock: Clock[IO] = TestClocks.makeFakeClock(1_000_000_000_000L)
  ): (InMemoryHabitRepository, InMemoryHabitCompletionRepository, HabitCompletionService) = {
    val svc = new DefaultHabitCompletionService(habitRepo, completionRepo, clock)
    (habitRepo, completionRepo, svc)
  }

  private val today     = LocalDate.of(2026, 4, 17)
  private val yesterday = today.minusDays(1)
  private val tomorrow  = today.plusDays(1)

  // ---------------------------------------------------------------------------
  // recordCompletion tests
  // ---------------------------------------------------------------------------

  "HabitCompletionService.recordCompletion" should {

    "return NotFound when the habit does not exist" in {
      val (_, _, svc) = makeService()
      val req         = CreateHabitCompletionRequest(today, None)
      svc.recordCompletion(UUID.randomUUID(), req).asserting { result =>
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[NotFound]
      }
    }

    "return NotFound when the habit is soft-deleted" in {
      val habitRepo = new InMemoryHabitRepository()
      val habit     = makeHabit(deletedAt = Some(Instant.now()))
      val (_, _, svc) = makeService(habitRepo = habitRepo)
      habitRepo.store.put(habit.id, habit)
      val req = CreateHabitCompletionRequest(today, None)
      svc.recordCompletion(habit.id, req).asserting { result =>
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[NotFound]
      }
    }

    "return ConflictError when a completion already exists for the same (habitId, completedOn)" in {
      val habitRepo      = new InMemoryHabitRepository()
      val completionRepo = new InMemoryHabitCompletionRepository()
      val (_, _, svc)    = makeService(habitRepo = habitRepo, completionRepo = completionRepo)
      val habit          = makeHabit()
      habitRepo.store.put(habit.id, habit)
      val req = CreateHabitCompletionRequest(today, None)

      for {
        _      <- svc.recordCompletion(habit.id, req)
        result <- svc.recordCompletion(habit.id, req)
      } yield {
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[ConflictError]
      }
    }

    "return Right with all fields populated on success" in {
      val habitRepo      = new InMemoryHabitRepository()
      val completionRepo = new InMemoryHabitCompletionRepository()
      val (_, _, svc)    = makeService(habitRepo = habitRepo, completionRepo = completionRepo)
      val habit          = makeHabit()
      habitRepo.store.put(habit.id, habit)
      val req = CreateHabitCompletionRequest(today, Some("Felt energised"))

      svc.recordCompletion(habit.id, req).asserting { result =>
        result.isRight shouldBe true
        val resp = result.toOption.get
        resp.id should not be null
        resp.habitId shouldBe habit.id
        resp.completedOn shouldBe today
        resp.note shouldBe Some("Felt energised")
        resp.createdAt should not be null
      }
    }

    "return Right with note as None when not provided" in {
      val habitRepo      = new InMemoryHabitRepository()
      val completionRepo = new InMemoryHabitCompletionRepository()
      val (_, _, svc)    = makeService(habitRepo = habitRepo, completionRepo = completionRepo)
      val habit          = makeHabit()
      habitRepo.store.put(habit.id, habit)
      val req = CreateHabitCompletionRequest(today, None)

      svc.recordCompletion(habit.id, req).asserting { result =>
        result.isRight shouldBe true
        result.toOption.get.note shouldBe None
      }
    }
  }

  // ---------------------------------------------------------------------------
  // listCompletions tests
  // ---------------------------------------------------------------------------

  "HabitCompletionService.listCompletions" should {

    "return NotFound for a missing habit" in {
      val (_, _, svc) = makeService()
      svc.listCompletions(UUID.randomUUID(), None, None).asserting { result =>
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[NotFound]
      }
    }

    "return an empty list for a habit with no completions" in {
      val habitRepo      = new InMemoryHabitRepository()
      val completionRepo = new InMemoryHabitCompletionRepository()
      val (_, _, svc)    = makeService(habitRepo = habitRepo, completionRepo = completionRepo)
      val habit          = makeHabit()
      habitRepo.store.put(habit.id, habit)

      svc.listCompletions(habit.id, None, None).asserting { result =>
        result shouldBe Right(List.empty)
      }
    }

    "apply from filter correctly" in {
      val habitRepo      = new InMemoryHabitRepository()
      val completionRepo = new InMemoryHabitCompletionRepository()
      val (_, _, svc)    = makeService(habitRepo = habitRepo, completionRepo = completionRepo)
      val habit          = makeHabit()
      habitRepo.store.put(habit.id, habit)

      for {
        _ <- svc.recordCompletion(habit.id, CreateHabitCompletionRequest(yesterday, None))
        _ <- svc.recordCompletion(habit.id, CreateHabitCompletionRequest(today, None))
        _ <- svc.recordCompletion(habit.id, CreateHabitCompletionRequest(tomorrow, None))
        result <- svc.listCompletions(habit.id, Some(today), None)
      } yield {
        result.isRight shouldBe true
        val list = result.toOption.get
        list.length shouldBe 2
        list.map(_.completedOn) should contain(today)
        list.map(_.completedOn) should contain(tomorrow)
        list.map(_.completedOn) should not contain yesterday
      }
    }

    "apply to filter correctly" in {
      val habitRepo      = new InMemoryHabitRepository()
      val completionRepo = new InMemoryHabitCompletionRepository()
      val (_, _, svc)    = makeService(habitRepo = habitRepo, completionRepo = completionRepo)
      val habit          = makeHabit()
      habitRepo.store.put(habit.id, habit)

      for {
        _ <- svc.recordCompletion(habit.id, CreateHabitCompletionRequest(yesterday, None))
        _ <- svc.recordCompletion(habit.id, CreateHabitCompletionRequest(today, None))
        _ <- svc.recordCompletion(habit.id, CreateHabitCompletionRequest(tomorrow, None))
        result <- svc.listCompletions(habit.id, None, Some(today))
      } yield {
        result.isRight shouldBe true
        val list = result.toOption.get
        list.length shouldBe 2
        list.map(_.completedOn) should contain(yesterday)
        list.map(_.completedOn) should contain(today)
        list.map(_.completedOn) should not contain tomorrow
      }
    }

    "return results ordered by completedOn DESC" in {
      val habitRepo      = new InMemoryHabitRepository()
      val completionRepo = new InMemoryHabitCompletionRepository()
      val (_, _, svc)    = makeService(habitRepo = habitRepo, completionRepo = completionRepo)
      val habit          = makeHabit()
      habitRepo.store.put(habit.id, habit)

      for {
        _ <- svc.recordCompletion(habit.id, CreateHabitCompletionRequest(yesterday, None))
        _ <- svc.recordCompletion(habit.id, CreateHabitCompletionRequest(today, None))
        _ <- svc.recordCompletion(habit.id, CreateHabitCompletionRequest(tomorrow, None))
        result <- svc.listCompletions(habit.id, None, None)
      } yield {
        result.isRight shouldBe true
        val dates = result.toOption.get.map(_.completedOn)
        dates shouldBe List(tomorrow, today, yesterday)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // deleteCompletion tests
  // ---------------------------------------------------------------------------

  "HabitCompletionService.deleteCompletion" should {

    "return NotFound for a missing habit" in {
      val (_, _, svc) = makeService()
      svc.deleteCompletion(UUID.randomUUID(), UUID.randomUUID()).asserting { result =>
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[NotFound]
      }
    }

    "return NotFound when the completion does not exist" in {
      val habitRepo      = new InMemoryHabitRepository()
      val completionRepo = new InMemoryHabitCompletionRepository()
      val (_, _, svc)    = makeService(habitRepo = habitRepo, completionRepo = completionRepo)
      val habit          = makeHabit()
      habitRepo.store.put(habit.id, habit)

      svc.deleteCompletion(habit.id, UUID.randomUUID()).asserting { result =>
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[NotFound]
      }
    }

    "return NotFound when the completion belongs to a different habit" in {
      val habitRepo      = new InMemoryHabitRepository()
      val completionRepo = new InMemoryHabitCompletionRepository()
      val (_, _, svc)    = makeService(habitRepo = habitRepo, completionRepo = completionRepo)
      val habit1         = makeHabit()
      val habit2         = makeHabit()
      habitRepo.store.put(habit1.id, habit1)
      habitRepo.store.put(habit2.id, habit2)

      for {
        created <- svc.recordCompletion(habit2.id, CreateHabitCompletionRequest(today, None))
        completionId = created.toOption.get.id
        // Try to delete the completion from habit2 using habit1's id
        result <- svc.deleteCompletion(habit1.id, completionId)
      } yield {
        result.isLeft shouldBe true
        result.swap.toOption.get shouldBe a[NotFound]
      }
    }

    "return Right(()) on successful deletion and list no longer contains the row" in {
      val habitRepo      = new InMemoryHabitRepository()
      val completionRepo = new InMemoryHabitCompletionRepository()
      val (_, _, svc)    = makeService(habitRepo = habitRepo, completionRepo = completionRepo)
      val habit          = makeHabit()
      habitRepo.store.put(habit.id, habit)

      for {
        created      <- svc.recordCompletion(habit.id, CreateHabitCompletionRequest(today, None))
        completionId  = created.toOption.get.id
        deleteResult <- svc.deleteCompletion(habit.id, completionId)
        listResult   <- svc.listCompletions(habit.id, None, None)
      } yield {
        deleteResult shouldBe Right(())
        listResult shouldBe Right(List.empty)
      }
    }
  }
}
