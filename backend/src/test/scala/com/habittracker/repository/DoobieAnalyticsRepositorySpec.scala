package com.habittracker.repository

import org.junit.Ignore
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.IO
import cats.effect.unsafe.IORuntime
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
//@Ignore
@RunWith(classOf[JUnitRunner])
class DoobieAnalyticsRepositorySpec
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

  private var transactor:    HikariTransactor[IO]              = _
  private var habitRepo:     DoobieHabitRepository             = _
  private var completionRepo: DoobieHabitCompletionRepository  = _
  private var analyticsRepo: DoobieAnalyticsRepository         = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    container.start()

    // Run Liquibase migrations
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
        Paths.get("../infra/db/changelog").toAbsolutePath.normalize
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
    analyticsRepo  = new DoobieAnalyticsRepository(transactor)
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

  private def makeHabit(name: String = "Test habit", userId: Long = 1L): Habit = {
    val now = Instant.now()
    Habit(UUID.randomUUID(), userId, name, None, Frequency.Daily, now, now, None)
  }

  private def makeCompletion(
      habitId: UUID,
      completedOn: LocalDate = LocalDate.now(),
      note: Option[String] = None
  ): HabitCompletion =
    HabitCompletion(UUID.randomUUID(), habitId, completedOn, note, Instant.now())

  private def run[A](io: IO[A]): A = io.unsafeRunSync()

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  "streakForHabit" should {

    "return 3 when the habit was completed on today and the 2 prior days" in {
      val habit = makeHabit()
      run(habitRepo.create(habit))

      val today      = LocalDate.now()
      val yesterday  = today.minusDays(1)
      val twoDaysAg  = today.minusDays(2)

      run(completionRepo.create(makeCompletion(habit.id, today)))
      run(completionRepo.create(makeCompletion(habit.id, yesterday)))
      run(completionRepo.create(makeCompletion(habit.id, twoDaysAg)))

      val streak = run(analyticsRepo.streakForHabit(habit.id))
      streak shouldBe 3
    }

    "return 0 when yesterday has no completion, even if today does not" in {
      val habit = makeHabit()
      run(habitRepo.create(habit))

      val today        = LocalDate.now()
      val threeDaysAgo = today.minusDays(3)
      val fourDaysAgo  = today.minusDays(4)

      // No completion for today, no completion for yesterday, no completion
      // for two days ago; completions only exist 3-4 days back. Streak = 0.
      run(completionRepo.create(makeCompletion(habit.id, threeDaysAgo)))
      run(completionRepo.create(makeCompletion(habit.id, fourDaysAgo)))

      val streak = run(analyticsRepo.streakForHabit(habit.id))
      streak shouldBe 0
    }
  }

  "completionRateByDayOfWeek" should {

    "return expected rates for seeded completions" in {
      val habit = makeHabit()
      run(habitRepo.create(habit))

      // Seed two Mondays and one Tuesday, all within the last 14 days.
      // TODO: test is incorrect when running on Wednesday. weekSpan is 3 weeks, not 2
      val today    = LocalDate.now()
      val monday2w  = today.`with`(java.time.DayOfWeek.MONDAY).minusWeeks(1)
      val tuesday2w = monday2w.plusDays(1)
      val monday1w  = monday2w.plusWeeks(1)

      run(completionRepo.create(makeCompletion(habit.id, monday2w)))
      run(completionRepo.create(makeCompletion(habit.id, tuesday2w)))
      run(completionRepo.create(makeCompletion(habit.id, monday1w)))

      val result = run(analyticsRepo.completionRateByDayOfWeek(1L))

      result("Monday")    shouldBe 1.0 +- 0.01
      result("Tuesday")   shouldBe 0.5 +- 0.01
      result("Wednesday") shouldBe 0.0
      result("Thursday")  shouldBe 0.0
      result("Friday")    shouldBe 0.0
      result("Saturday")  shouldBe 0.0
      result("Sunday")    shouldBe 0.0

      result.keySet shouldBe Set("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    }
  }

  "habitConsistencyRanking" should {

    "return habits sorted descending by score" in {
      val habit1 = makeHabit(name = "High-consistency")
      val habit2 = makeHabit(name = "Low-consistency")
      run(habitRepo.create(habit1))
      run(habitRepo.create(habit2))

      // Seed 5 completions for habit1, 1 completion for habit2. The
      // denominator (days-since-created) is the same for both, so
      // habit1 outranks habit2 on sheer count.
      val today = LocalDate.now()
      (0 to 4).foreach { i =>
        run(completionRepo.create(makeCompletion(habit1.id, today.minusDays(i.toLong))))
      }
      run(completionRepo.create(makeCompletion(habit2.id, today)))

      val ranking = run(analyticsRepo.habitConsistencyRanking(1L))

      ranking.map(_._1) shouldBe List("High-consistency", "Low-consistency")
      ranking.head._2 should be > ranking(1)._2
    }
  }
}
