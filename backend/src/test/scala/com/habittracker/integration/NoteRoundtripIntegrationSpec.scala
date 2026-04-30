package com.habittracker.integration

// requires Docker - run manually
// Also requires OPENAI_API_KEY and ANTHROPIC_API_KEY environment variables.
import org.junit.Ignore
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.{Clock, IO}
import cats.effect.unsafe.IORuntime
import cats.syntax.semigroupk._
import com.comcast.ip4s._
import com.habittracker.http.AnalyticsCodecs._
import com.habittracker.http.{
  AnalysisRoutes, BatchCompletionRoutes, DocsRoutes, HabitCompletionRoutes,
  HabitRoutes, InsightsRoutes, NoteRoutes, TipsRoutes
}
import com.habittracker.model.{TipsResponse, UserNote}
import com.habittracker.repository.{
  DoobieAnalyticsRepository,
  DoobieHabitCompletionRepository,
  DoobieHabitRepository,
  NoteRepository,
  TipRepository
}
import com.habittracker.service.{DefaultAnalyticsService, DefaultHabitCompletionService, DefaultHabitService}
import doobie.hikari.HikariTransactor
import doobie.implicits._
import io.circe.parser.decode
import org.http4s.ember.server.EmberServerBuilder
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse => JHttpResponse}
import scala.concurrent.ExecutionContext

@Ignore
@RunWith(classOf[JUnitRunner])
class NoteRoundtripIntegrationSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  implicit val ioRuntime: IORuntime = IORuntime.global

  // pgvector image required for the vector extension
  private val container: PostgreSQLContainer[Nothing] =
    new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17"))

  private var transactor:    HikariTransactor[IO] = _
  private var serverShutdown: IO[Unit]            = _
  private var baseUrl: String                     = _
  private val httpClient: HttpClient              = HttpClient.newHttpClient()
  private var testUserId: Long                    = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    container.start()

    // -------------------------------------------------------------------------
    // Run Liquibase migrations (users, habits, completions tables)
    // -------------------------------------------------------------------------
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
      val liq = new Liquibase("db.changelog-master.xml", accessor, database)
      liq.update("")
    } finally {
      jdbcConn.close()
    }

    // -------------------------------------------------------------------------
    // Apply init DDL for habit_tips and user_notes (vector tables, not Liquibase)
    // -------------------------------------------------------------------------
    val connectEC = ExecutionContext.fromExecutor(
      java.util.concurrent.Executors.newFixedThreadPool(4)
    )
    transactor = HikariTransactor
      .newHikariTransactor[IO](
        "org.postgresql.Driver",
        container.getJdbcUrl,
        container.getUsername,
        container.getPassword,
        connectEC
      )
      .allocated
      .unsafeRunSync()
      ._1

    // habit_tips and user_notes tables are created here (same DDL as init scripts)
    (for {
      _ <- sql"""CREATE TABLE IF NOT EXISTS habit_tips (
                   id        BIGSERIAL    PRIMARY KEY,
                   content   TEXT         NOT NULL,
                   embedding vector(1536) NOT NULL
                 )""".update.run
      _ <- sql"""CREATE UNIQUE INDEX IF NOT EXISTS uq_habit_tips_content
                   ON habit_tips (md5(content))""".update.run
      _ <- sql"""CREATE TABLE IF NOT EXISTS user_notes (
                   id         BIGSERIAL    PRIMARY KEY,
                   user_id    BIGINT       NOT NULL,
                   content    TEXT         NOT NULL,
                   embedding  vector(1536) NOT NULL,
                   created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
                 )""".update.run
      _ <- sql"""CREATE INDEX IF NOT EXISTS user_notes_user_id_idx
                   ON user_notes (user_id)""".update.run
    } yield ()).transact(transactor).unsafeRunSync()

    // Seed a test user
    testUserId = sql"INSERT INTO users (email) VALUES ('integration@test.com') RETURNING id"
      .query[Long]
      .unique
      .transact(transactor)
      .unsafeRunSync()

    // Wire all routes
    val habitRepo        = new DoobieHabitRepository(transactor)
    val completionRepo   = new DoobieHabitCompletionRepository(transactor)
    val analyticsRepo    = new DoobieAnalyticsRepository(transactor)
    val tipRepo          = new TipRepository(transactor)
    val noteRepo         = new NoteRepository(transactor)
    val habitService     = new DefaultHabitService(habitRepo, Clock[IO])
    val completionSvc    = new DefaultHabitCompletionService(habitRepo, completionRepo, Clock[IO])
    val analyticsService = new DefaultAnalyticsService(habitRepo, analyticsRepo)

    val allRoutes =
      new DocsRoutes().routes <+>
      new InsightsRoutes(analyticsService).routes <+>
      new AnalysisRoutes(analyticsService).routes <+>
      new TipsRoutes(analyticsService, tipRepo, noteRepo).routes <+>
      new BatchCompletionRoutes(completionSvc).routes <+>
      new NoteRoutes(noteRepo).routes <+>
      new HabitRoutes(habitService).routes <+>
      new HabitCompletionRoutes(completionSvc).routes

    val (server, shutdown) = EmberServerBuilder
      .default[IO]
      .withHost(ip"127.0.0.1")
      .withPort(port"0")
      .withHttpApp(allRoutes.orNotFound)
      .build
      .allocated
      .unsafeRunSync()

    serverShutdown = shutdown
    val port = server.address.getPort
    baseUrl = s"http://127.0.0.1:$port"
  }

  override def afterAll(): Unit = {
    serverShutdown.unsafeRunSync()
    container.stop()
    super.afterAll()
  }

  override def beforeEach(): Unit =
    sql"DELETE FROM user_notes".update.run.transact(transactor).unsafeRunSync()

  // ---------------------------------------------------------------------------
  // Tests (PBI-018 AC-19)
  // ---------------------------------------------------------------------------

  "NoteRoundtrip" should {

    "POST /notes then GET /tips — note appears in personalNotes" in {
      val noteContent = "I run better in the morning"

      // Step 1: POST the note
      val postRequest = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl/users/$testUserId/habits/notes"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(s"""{"content": "$noteContent"}"""))
        .build()

      val postResp = httpClient.send(postRequest, JHttpResponse.BodyHandlers.ofString())
      postResp.statusCode() shouldBe 201

      val userNote = decode[UserNote](postResp.body())
      userNote.isRight shouldBe true
      userNote.toOption.get.id should be > 0L

      // Step 2: GET /tips
      val getRequest = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl/users/$testUserId/habits/tips"))
        .GET()
        .build()

      val getResp = httpClient.send(getRequest, JHttpResponse.BodyHandlers.ofString())
      getResp.statusCode() shouldBe 200

      val tipsResponse = decode[TipsResponse](getResp.body())
      tipsResponse.isRight shouldBe true
      val response = tipsResponse.toOption.get

      // personalNotes should contain the posted note (or a retrieved near-match)
      response.personalNotes should not be empty
    }
  }
}
