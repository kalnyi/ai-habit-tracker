package com.habittracker.repository

// requires Docker - run manually
import org.junit.Ignore
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import doobie.hikari.HikariTransactor
import doobie.implicits._
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

import scala.concurrent.ExecutionContext
import scala.util.Random

@Ignore
@RunWith(classOf[JUnitRunner])
class NoteRepositorySpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  implicit val runtime: IORuntime = IORuntime.global

  // ---------------------------------------------------------------------------
  // Testcontainers — pgvector image required for the `vector` extension
  // ---------------------------------------------------------------------------

  private val container: PostgreSQLContainer[Nothing] =
    new PostgreSQLContainer(
      DockerImageName.parse("pgvector/pgvector:pg17")
    )

  private var transactor: HikariTransactor[IO] = _
  private var noteRepo:   NoteRepository       = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    container.start()

    val jdbcUrl  = container.getJdbcUrl
    val user     = container.getUsername
    val password = container.getPassword

    val connectEC = ExecutionContext.fromExecutor(
      java.util.concurrent.Executors.newFixedThreadPool(4)
    )
    transactor = HikariTransactor
      .newHikariTransactor[IO](
        "org.postgresql.Driver",
        jdbcUrl,
        user,
        password,
        connectEC
      )
      .allocated
      .unsafeRunSync()
      ._1

    // Enable extension and create user_notes table
    (for {
      _ <- sql"CREATE EXTENSION IF NOT EXISTS vector".update.run
      _ <- sql"""
             CREATE TABLE IF NOT EXISTS user_notes (
               id         BIGSERIAL    PRIMARY KEY,
               user_id    BIGINT       NOT NULL,
               content    TEXT         NOT NULL,
               embedding  vector(1536) NOT NULL,
               created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
             )""".update.run
      _ <- sql"""CREATE INDEX IF NOT EXISTS user_notes_user_id_idx
                   ON user_notes (user_id)""".update.run
    } yield ()).transact(transactor).unsafeRunSync()

    noteRepo = new NoteRepository(transactor)
  }

  override def afterAll(): Unit = {
    container.stop()
    super.afterAll()
  }

  override def beforeEach(): Unit =
    sql"DELETE FROM user_notes".update.run.transact(transactor).unsafeRunSync()

  // ---------------------------------------------------------------------------
  // Helper: random 1536-element vector
  // ---------------------------------------------------------------------------

  private def randomVector(): Vector[Float] =
    Vector.fill(1536)(Random.nextFloat())

  // ---------------------------------------------------------------------------
  // Tests (PBI-018 AC-17)
  // ---------------------------------------------------------------------------

  "NoteRepository.insert" should {

    "store a note with embedding and return UserNote with a non-null generated id" in {
      val embedding = randomVector()
      val note      = noteRepo.insert(42L, "I run better in the morning", embedding).unsafeRunSync()

      note.id should be > 0L
      note.userId shouldBe 42L
      note.content shouldBe "I run better in the morning"
      note.createdAt should not be null
    }
  }

  "NoteRepository.findSimilar" should {

    "return exactly topK results ordered by similarityScore descending" in {
      val topK = 3
      // Seed 10 notes with random embeddings for the same userId
      (1 to 10).foreach { i =>
        noteRepo.insert(1L, s"Note number $i", randomVector()).unsafeRunSync()
      }
      val query   = randomVector()
      val results = noteRepo.findSimilar(1L, query, topK).unsafeRunSync()

      results.length shouldBe topK
      results.head.similarityScore should be >= results.last.similarityScore
    }

    "not return notes belonging to a different userId (userId filter)" in {
      // Seed 5 notes for user 1 and 5 notes for user 2
      val user1Notes = (1 to 5).map { i =>
        noteRepo.insert(1L, s"User1 note $i", randomVector()).unsafeRunSync()
      }.toList
      (1 to 5).foreach { i =>
        noteRepo.insert(2L, s"User2 note $i", randomVector()).unsafeRunSync()
      }

      val query   = randomVector()
      // topK = 5 and user 1 has exactly 5 rows
      val results = noteRepo.findSimilar(1L, query, 5).unsafeRunSync()

      // All returned note ids must belong to user 1 inserts
      val user1Ids = user1Notes.map(_.id).toSet
      results.foreach { r =>
        user1Ids should contain(r.tip.id)
      }
      // No results from user 2
      results.length shouldBe 5
    }

    "return empty list without error when user_notes table is empty" in {
      val query   = randomVector()
      val results = noteRepo.findSimilar(1L, query, 3).unsafeRunSync()

      results shouldBe Nil
    }
  }
}
