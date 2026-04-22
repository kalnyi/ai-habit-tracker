package com.habittracker.repository

import org.junit.Ignore
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.habittracker.domain.{Frequency, Habit}
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

import java.time.Instant
import java.util.UUID

// requires Docker - run manually
@Ignore
@RunWith(classOf[JUnitRunner])
class DoobieHabitRepositorySpec
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

  private var transactor: HikariTransactor[IO] = _
  private var repo: DoobieHabitRepository      = _

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

    repo = new DoobieHabitRepository(transactor)
  }

  override def afterAll(): Unit = {
    container.stop()
    super.afterAll()
  }

  override def beforeEach(): Unit = {
    // Clean the habits table before each test for isolation
    sql"DELETE FROM habits".update.run.transact(transactor).unsafeRunSync()
  }

  // ---------------------------------------------------------------------------
  // Helper
  // ---------------------------------------------------------------------------

  private def makeHabit(
      name: String = "Test habit",
      description: Option[String] = None,
      frequency: Frequency = Frequency.Daily,
      userId: Long = 1L,
      deletedAt: Option[Instant] = None
  ): Habit = {
    val now = Instant.now()
    Habit(UUID.randomUUID(), userId, name, description, frequency, now, now, deletedAt)
  }

  private def run[A](io: IO[A]): A = io.unsafeRunSync()

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  "DoobieHabitRepository.create and findActiveById" should {

    "round-trip every field including a null description" in {
      val habit = makeHabit(name = "Read", description = None)
      run(repo.create(habit))

      val found = run(repo.findActiveById(1L, habit.id))
      found should not be empty
      val h = found.get
      h.id shouldBe habit.id
      h.userId shouldBe 1L
      h.name shouldBe "Read"
      h.description shouldBe None
      h.frequency shouldBe Frequency.Daily
      h.createdAt.toEpochMilli shouldBe habit.createdAt.toEpochMilli +- 1000
      h.updatedAt.toEpochMilli shouldBe habit.updatedAt.toEpochMilli +- 1000
      h.deletedAt shouldBe None
    }

    "round-trip a habit with a non-null description" in {
      val habit = makeHabit(name = "Exercise", description = Some("30 minutes"))
      run(repo.create(habit))

      val found = run(repo.findActiveById(1L, habit.id))
      found.map(_.description) shouldBe Some(Some("30 minutes"))
    }

    "return None for a non-existent id" in {
      val result = run(repo.findActiveById(1L, UUID.randomUUID()))
      result shouldBe None
    }

    "return None for a soft-deleted habit" in {
      val habit = makeHabit()
      run(repo.create(habit))
      run(repo.softDelete(1L, habit.id, Instant.now()))

      val result = run(repo.findActiveById(1L, habit.id))
      result shouldBe None
    }

    "return None when habit belongs to a different user (DB-layer ownership filter)" in {
      val habit = makeHabit(userId = 1L)
      run(repo.create(habit))

      val result = run(repo.findActiveById(2L, habit.id))
      result shouldBe None
    }
  }

  "DoobieHabitRepository.listActive" should {

    "return only rows with deleted_at IS NULL, ordered by created_at DESC" in {
      val h1 = makeHabit(name = "First")
      Thread.sleep(10) // ensure different created_at
      val h2 = makeHabit(name = "Second")
      val h3 = makeHabit(name = "Third - to be deleted")

      run(repo.create(h1))
      run(repo.create(h2))
      run(repo.create(h3))
      run(repo.softDelete(1L, h3.id, Instant.now()))

      val list = run(repo.listActive(1L))
      list.length shouldBe 2
      list.map(_.name) should contain("First")
      list.map(_.name) should contain("Second")
      list.map(_.name) should not contain "Third - to be deleted"
      // Newest first
      list.head.name shouldBe "Second"
    }

    "return empty list when no active habits exist" in {
      val list = run(repo.listActive(1L))
      list shouldBe empty
    }
  }

  "DoobieHabitRepository.updateActive" should {

    "update name, description, frequency and return the updated habit" in {
      val habit = makeHabit(name = "Old name", description = None, frequency = Frequency.Daily)
      run(repo.create(habit))

      val updatedAt = Instant.now().plusSeconds(1)
      val result = run(
        repo.updateActive(1L, habit.id, "New name", Some("New desc"), Frequency.Weekly, updatedAt)
      )

      result should not be empty
      val h = result.get
      h.name shouldBe "New name"
      h.description shouldBe Some("New desc")
      h.frequency shouldBe Frequency.Weekly
      h.updatedAt.toEpochMilli shouldBe updatedAt.toEpochMilli +- 1000
      // id and createdAt unchanged
      h.id shouldBe habit.id
      h.createdAt.toEpochMilli shouldBe habit.createdAt.toEpochMilli +- 1000
    }

    "return None when habit does not exist" in {
      val result = run(
        repo.updateActive(1L, UUID.randomUUID(), "name", None, Frequency.Daily, Instant.now())
      )
      result shouldBe None
    }

    "return None and not update a soft-deleted row" in {
      val habit = makeHabit()
      run(repo.create(habit))
      run(repo.softDelete(1L, habit.id, Instant.now()))

      val result = run(
        repo.updateActive(1L, habit.id, "New name", None, Frequency.Weekly, Instant.now())
      )
      result shouldBe None

      // Verify the soft-deleted row was not touched
      val raw = sql"SELECT name FROM habits WHERE id = ${habit.id}"
        .query[String]
        .option
        .transact(transactor)
        .unsafeRunSync()
      // Row is still present with original name
      raw shouldBe Some(habit.name)
    }
  }

  "DoobieHabitRepository.softDelete" should {

    "set deleted_at and return true on first call" in {
      val habit = makeHabit()
      run(repo.create(habit))

      val result = run(repo.softDelete(1L, habit.id, Instant.now()))
      result shouldBe true
    }

    "return false on second call (already deleted)" in {
      val habit = makeHabit()
      run(repo.create(habit))

      run(repo.softDelete(1L, habit.id, Instant.now()))
      val result = run(repo.softDelete(1L, habit.id, Instant.now()))
      result shouldBe false
    }

    "keep the row physically present with a non-null deleted_at (PBI-006 AC)" in {
      val habit = makeHabit()
      run(repo.create(habit))
      run(repo.softDelete(1L, habit.id, Instant.now()))

      // Raw SQL assertion: row still exists
      val rowCount = sql"SELECT COUNT(*) FROM habits WHERE id = ${habit.id}"
        .query[Long]
        .unique
        .transact(transactor)
        .unsafeRunSync()

      rowCount shouldBe 1L

      // And deleted_at is non-null (query as Option[Instant] using postgres implicits)
      val deletedAt = sql"SELECT deleted_at FROM habits WHERE id = ${habit.id}"
        .query[Option[Instant]]
        .option
        .transact(transactor)
        .unsafeRunSync()

      deletedAt.flatten should not be empty
    }

    "return false when deleting a non-existent id" in {
      val result = run(repo.softDelete(1L, UUID.randomUUID(), Instant.now()))
      result shouldBe false
    }
  }
}
