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
class TipRepositorySpec
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
  private var tipRepo:    TipRepository        = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    container.start()

    // Create extension and table directly; Liquibase changelog does not include
    // the habit_tips corpus table (it is infrastructure, not application schema).
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

    // Enable extension and create table
    (for {
      _ <- sql"CREATE EXTENSION IF NOT EXISTS vector".update.run
      _ <- sql"""
             CREATE TABLE IF NOT EXISTS habit_tips (
               id        BIGSERIAL    PRIMARY KEY,
               content   TEXT         NOT NULL,
               embedding vector(1536) NOT NULL
             )""".update.run
      _ <- sql"""CREATE UNIQUE INDEX IF NOT EXISTS uq_habit_tips_content
                   ON habit_tips (md5(content))""".update.run
    } yield ()).transact(transactor).unsafeRunSync()

    tipRepo = new TipRepository(transactor)
  }

  override def afterAll(): Unit = {
    container.stop()
    super.afterAll()
  }

  override def beforeEach(): Unit =
    sql"DELETE FROM habit_tips".update.run.transact(transactor).unsafeRunSync()

  // ---------------------------------------------------------------------------
  // Helper: random 1536-element vector
  // ---------------------------------------------------------------------------

  private def randomVector(): Vector[Float] =
    Vector.fill(1536)(Random.nextFloat())

  // ---------------------------------------------------------------------------
  // Tests (PBI-015 AC-19)
  // ---------------------------------------------------------------------------

  "TipRepository.insert" should {

    "store a tip and return a HabitTip with a non-zero generated id" in {
      val embedding = randomVector()
      val tip       = tipRepo.insert("A test tip", embedding).unsafeRunSync()

      tip.id should be > 0L
      tip.content shouldBe "A test tip"
    }
  }

  "TipRepository.findSimilar" should {

    "return exactly topK results when the table contains at least topK rows" in {
      val topK = 3
      // Seed 10 tips with random embeddings
      (1 to 10).foreach { i =>
        tipRepo.insert(s"Tip number $i", randomVector()).unsafeRunSync()
      }
      val query   = randomVector()
      val results = tipRepo.findSimilar(query, topK).unsafeRunSync()

      results.length shouldBe topK
    }

    "return results ordered by similarityScore descending" in {
      val topK = 3
      (1 to 10).foreach { i =>
        tipRepo.insert(s"Ordered tip $i", randomVector()).unsafeRunSync()
      }
      val query   = randomVector()
      val results = tipRepo.findSimilar(query, topK).unsafeRunSync()

      results.length should be > 1
      results.head.similarityScore should be >= results.last.similarityScore
    }

    "return an empty list without error when habit_tips table is empty" in {
      val query   = randomVector()
      val results = tipRepo.findSimilar(query, 3).unsafeRunSync()

      results shouldBe Nil
    }
  }
}
