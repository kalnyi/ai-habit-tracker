package com.habittracker.repository

import org.junit.Ignore
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.habittracker.domain.AppError.ConflictError
import com.habittracker.domain.{Frequency, Habit, HabitCompletion}
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

import java.time.{Instant, LocalDate}
import java.util.UUID

// requires Docker - run manually
@Ignore
@RunWith(classOf[JUnitRunner])
class DoobieHabitCompletionRepositorySpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  implicit val runtime: IORuntime = IORuntime.global

  // ---------------------------------------------------------------------------
  // Testcontainers PostgreSQL
  // ---------------------------------------------------------------------------

  private val container: PostgreSQLContainer[Nothing] =
    new PostgreSQLContainer(
      DockerImageName.parse("postgres:17-alpine")
    )

  private var transactor: HikariTransactor[IO]             = _
  private var habitRepo: DoobieHabitRepository             = _
  private var completionRepo: DoobieHabitCompletionRepository = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    container.start()

    import liquibase.Liquibase
    import liquibase.database.DatabaseFactory
    import liquibase.database.jvm.JdbcConnection
    import liquibase.resource.DirectoryResourceAccessor
    import java.nio.file.Paths
    import java.sql.DriverManager

    val jdbcConn = DriverManager.getConnection(
      container.getJdbcUrl, container.getUsername, container.getPassword
    )
    try {
      val database = DatabaseFactory.getInstance()
        .findCorrectDatabaseImplementation(new JdbcConnection(jdbcConn))
      val accessor = new DirectoryResourceAccessor(
        Paths.get("../../infra/db/changelog").toAbsolutePath.normalize
      )
      val liquibase = new Liquibase("db.changelog-master.xml", accessor, database)
      liquibase.update("")
    } finally {
      jdbcConn.close()
    }

    transactor = HikariTransactor
      .newHikariTransactor[IO](
        "org.postgresql.Driver",
        container.getJdbcUrl,
        container.getUsername,
        container.getPassword,
        runtime.compute
      )
      .allocated
      .unsafeRunSync()
      ._1

    habitRepo      = new DoobieHabitRepository(transactor)
    completionRepo = new DoobieHabitCompletionRepository(transactor)
  }

  override def afterAll(): Unit = {
    container.stop()
    super.afterAll()
  }

  override def beforeEach(): Unit = {
    sql"DELETE FROM habit_completions".update.run.transact(transactor).unsafeRunSync()
    sql"DELETE FROM habits".update.run.transact(transactor).unsafeRunSync()
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def makeHabit(name: String = "Test habit"): Habit = {
    val now = Instant.now()
    Habit(UUID.randomUUID(), name, None, Frequency.Daily, now, now, None)
  }

  private def makeCompletion(
      habitId: UUID,
      completedOn: LocalDate = LocalDate.of(2026, 4, 17),
      note: Option[String] = None
  ): HabitCompletion =
    HabitCompletion(UUID.randomUUID(), habitId, completedOn, note, Instant.now())

  private def run[A](io: IO[A]): A = io.unsafeRunSync()

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  "DoobieHabitCompletionRepository.create" should {

    "persist a completion and return Right(())" in {
      val habit = makeHabit()
      run(habitRepo.create(habit))

      val completion = makeCompletion(habit.id)
      val result     = run(completionRepo.create(completion))

      result shouldBe Right(())

      val found = run(completionRepo.findByHabitAndDate(habit.id, completion.completedOn))
      found should not be empty
      found.get.id shouldBe completion.id
    }

    "return Left(ConflictError) on duplicate (habit_id, completed_on)" in {
      val habit = makeHabit()
      run(habitRepo.create(habit))

      val date = LocalDate.of(2026, 4, 17)
      run(completionRepo.create(makeCompletion(habit.id, date)))
      val result = run(completionRepo.create(makeCompletion(habit.id, date)))

      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe a[ConflictError]
    }
  }

  "DoobieHabitCompletionRepository.findByHabitAndDate" should {

    "return Some for a matching record" in {
      val habit = makeHabit()
      run(habitRepo.create(habit))
      val c = makeCompletion(habit.id)
      run(completionRepo.create(c))

      val found = run(completionRepo.findByHabitAndDate(habit.id, c.completedOn))
      found.map(_.id) shouldBe Some(c.id)
    }

    "return None when no matching record exists" in {
      val habit = makeHabit()
      run(habitRepo.create(habit))

      val result = run(completionRepo.findByHabitAndDate(habit.id, LocalDate.of(2000, 1, 1)))
      result shouldBe None
    }
  }

  "DoobieHabitCompletionRepository.listByHabit" should {

    "return all completions ordered by completed_on DESC when no filters" in {
      val habit = makeHabit()
      run(habitRepo.create(habit))

      val d1 = LocalDate.of(2026, 4, 15)
      val d2 = LocalDate.of(2026, 4, 16)
      val d3 = LocalDate.of(2026, 4, 17)

      run(completionRepo.create(makeCompletion(habit.id, d1)))
      run(completionRepo.create(makeCompletion(habit.id, d2)))
      run(completionRepo.create(makeCompletion(habit.id, d3)))

      val list = run(completionRepo.listByHabit(habit.id, None, None))
      list.length shouldBe 3
      list.map(_.completedOn) shouldBe List(d3, d2, d1)
    }

    "apply from and to filters correctly" in {
      val habit = makeHabit()
      run(habitRepo.create(habit))

      val d1 = LocalDate.of(2026, 4, 15)
      val d2 = LocalDate.of(2026, 4, 16)
      val d3 = LocalDate.of(2026, 4, 17)

      run(completionRepo.create(makeCompletion(habit.id, d1)))
      run(completionRepo.create(makeCompletion(habit.id, d2)))
      run(completionRepo.create(makeCompletion(habit.id, d3)))

      val list = run(completionRepo.listByHabit(habit.id, Some(d2), Some(d3)))
      list.length shouldBe 2
      list.map(_.completedOn) should contain(d2)
      list.map(_.completedOn) should contain(d3)
      list.map(_.completedOn) should not contain d1
    }
  }

  "DoobieHabitCompletionRepository.deleteByIdAndHabit" should {

    "return true and remove the row on a real match" in {
      val habit = makeHabit()
      run(habitRepo.create(habit))
      val c = makeCompletion(habit.id)
      run(completionRepo.create(c))

      val result = run(completionRepo.deleteByIdAndHabit(c.id, habit.id))
      result shouldBe true

      // Confirm hard delete
      val rowCount =
        sql"SELECT COUNT(*) FROM habit_completions WHERE id = ${c.id}"
          .query[Long]
          .unique
          .transact(transactor)
          .unsafeRunSync()
      rowCount shouldBe 0L
    }

    "return false when the completionId does not exist" in {
      val habit = makeHabit()
      run(habitRepo.create(habit))

      val result = run(completionRepo.deleteByIdAndHabit(UUID.randomUUID(), habit.id))
      result shouldBe false
    }

    "return false when the completion exists under a different habitId" in {
      val habit1 = makeHabit("Habit 1")
      val habit2 = makeHabit("Habit 2")
      run(habitRepo.create(habit1))
      run(habitRepo.create(habit2))

      val c = makeCompletion(habit2.id)
      run(completionRepo.create(c))

      // Try to delete c using habit1's id
      val result = run(completionRepo.deleteByIdAndHabit(c.id, habit1.id))
      result shouldBe false

      // Confirm row still exists
      val found = run(completionRepo.findByHabitAndDate(habit2.id, c.completedOn))
      found.map(_.id) shouldBe Some(c.id)
    }
  }

  "FK integrity" should {

    "raise an error when inserting a completion for a non-existent habitId" in {
      val c = makeCompletion(UUID.randomUUID())
      val result = scala.util.Try(run(completionRepo.create(c)))
      result.isFailure shouldBe true
    }
  }
}
