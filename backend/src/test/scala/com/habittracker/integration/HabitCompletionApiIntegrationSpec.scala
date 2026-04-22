package com.habittracker.integration

import org.junit.Ignore
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.{Clock, IO}
import cats.effect.unsafe.IORuntime
import cats.syntax.semigroupk._
import com.comcast.ip4s._
import com.habittracker.http.CompletionCodecs._
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.{
  CreateHabitCompletionRequest,
  CreateHabitRequest,
  HabitCompletionResponse,
  HabitResponse
}
import com.habittracker.http.{DocsRoutes, HabitCompletionRoutes, HabitRoutes}
import com.habittracker.repository.{DoobieHabitCompletionRepository, DoobieHabitRepository}
import com.habittracker.service.{DefaultHabitCompletionService, DefaultHabitService}
import doobie.hikari.HikariTransactor
import doobie.implicits._
import io.circe.parser.decode
import io.circe.syntax._
import org.http4s.ember.server.EmberServerBuilder
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse => JHttpResponse}
import java.time.LocalDate
import java.util.UUID
import scala.concurrent.ExecutionContext

// requires Docker - run manually
@Ignore
@RunWith(classOf[JUnitRunner])
class HabitCompletionApiIntegrationSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ScalaFutures {

  implicit val ioRuntime: IORuntime = IORuntime.global

  // ---------------------------------------------------------------------------
  // Infrastructure
  // ---------------------------------------------------------------------------

  private val container: PostgreSQLContainer[Nothing] =
    new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))

  private var transactor: HikariTransactor[IO] = _
  private var serverShutdown: IO[Unit]         = _
  private var baseUrl: String                  = _
  private val httpClient: HttpClient           = HttpClient.newHttpClient()

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
        Paths.get("../infra/db/changelog").toAbsolutePath.normalize
      )
      val liquibase = new Liquibase("db.changelog-master.xml", accessor, database)
      liquibase.update("")
    } finally {
      jdbcConn.close()
    }

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

    val habitRepo      = new DoobieHabitRepository(transactor)
    val completionRepo = new DoobieHabitCompletionRepository(transactor)
    val habitService   = new DefaultHabitService(habitRepo, Clock[IO])
    val completionSvc  = new DefaultHabitCompletionService(habitRepo, completionRepo, Clock[IO])
    val allRoutes      = new DocsRoutes().routes <+>
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
    baseUrl = s"http://127.0.0.1:${server.address.getPort}"
  }

  override def afterAll(): Unit = {
    serverShutdown.unsafeRunSync()
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

  private def sendPost(path: String, body: String): JHttpResponse[String] =
    httpClient.send(
      HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl$path"))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
        .build(),
      JHttpResponse.BodyHandlers.ofString()
    )

  private def sendGet(path: String): JHttpResponse[String] =
    httpClient.send(
      HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl$path"))
        .GET()
        .build(),
      JHttpResponse.BodyHandlers.ofString()
    )

  private def sendDelete(path: String): JHttpResponse[String] =
    httpClient.send(
      HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl$path"))
        .DELETE()
        .build(),
      JHttpResponse.BodyHandlers.ofString()
    )

  private def createHabit(name: String = "Run"): HabitResponse =
    decode[HabitResponse](
      sendPost("/habits", CreateHabitRequest(name, None, "daily").asJson.noSpaces).body()
    ).toOption.get

  private val today     = LocalDate.of(2026, 4, 17)
  private val yesterday = today.minusDays(1)
  private val tomorrow  = today.plusDays(1)

  // ---------------------------------------------------------------------------
  // PBI-009: Record a habit completion
  // ---------------------------------------------------------------------------

  "POST /habits/{habitId}/completions (PBI-009)" should {

    "return 201 with all expected response fields on valid input" in {
      val habit = createHabit()
      val resp  = sendPost(
        s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(today, Some("Felt energised")).asJson.noSpaces
      )
      resp.statusCode() shouldBe 201

      val completion = decode[HabitCompletionResponse](resp.body()).toOption.get
      completion.id should not be null
      completion.habitId shouldBe habit.id
      completion.completedOn shouldBe today
      completion.note shouldBe Some("Felt energised")
    }

    "return 404 for a non-existent habitId" in {
      val resp = sendPost(
        s"/habits/${UUID.randomUUID()}/completions",
        CreateHabitCompletionRequest(today, None).asJson.noSpaces
      )
      resp.statusCode() shouldBe 404
    }

    "return 409 on duplicate (habitId, completedOn)" in {
      val habit = createHabit()
      val body  = CreateHabitCompletionRequest(today, None).asJson.noSpaces

      sendPost(s"/habits/${habit.id}/completions", body).statusCode() shouldBe 201
      sendPost(s"/habits/${habit.id}/completions", body).statusCode() shouldBe 409
    }

    "return 400 when completedOn is not a valid date string" in {
      val habit = createHabit()
      sendPost(s"/habits/${habit.id}/completions", """{"completedOn":"not-a-date"}""")
        .statusCode() shouldBe 400
    }

    "return 400 when habitId is not a valid UUID" in {
      sendPost(
        "/habits/not-a-uuid/completions",
        CreateHabitCompletionRequest(today, None).asJson.noSpaces
      ).statusCode() shouldBe 400
    }
  }

  // ---------------------------------------------------------------------------
  // PBI-010: List completions for a habit
  // ---------------------------------------------------------------------------

  "GET /habits/{habitId}/completions (PBI-010)" should {

    "return completions ordered by completedOn DESC" in {
      val habit = createHabit()
      sendPost(s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(yesterday, None).asJson.noSpaces)
      sendPost(s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(today, None).asJson.noSpaces)
      sendPost(s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(tomorrow, None).asJson.noSpaces)

      val resp = sendGet(s"/habits/${habit.id}/completions")
      resp.statusCode() shouldBe 200
      val list = decode[List[HabitCompletionResponse]](resp.body()).toOption.get
      list.length shouldBe 3
      list.map(_.completedOn) shouldBe List(tomorrow, today, yesterday)
    }

    "apply from and to filters" in {
      val habit = createHabit()
      sendPost(s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(yesterday, None).asJson.noSpaces)
      sendPost(s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(today, None).asJson.noSpaces)
      sendPost(s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(tomorrow, None).asJson.noSpaces)

      val resp = sendGet(s"/habits/${habit.id}/completions?from=$today&to=$today")
      resp.statusCode() shouldBe 200
      val list = decode[List[HabitCompletionResponse]](resp.body()).toOption.get
      list.length shouldBe 1
      list.head.completedOn shouldBe today
    }

    "return 404 for a non-existent habitId" in {
      sendGet(s"/habits/${UUID.randomUUID()}/completions").statusCode() shouldBe 404
    }

    "return 200 with body [] for a habit with no completions" in {
      val habit = createHabit()
      val resp  = sendGet(s"/habits/${habit.id}/completions")
      resp.statusCode() shouldBe 200
      resp.body() shouldBe "[]"
    }

    "return 400 when from is not a valid date" in {
      val habit = createHabit()
      sendGet(s"/habits/${habit.id}/completions?from=not-a-date").statusCode() shouldBe 400
    }
  }

  // ---------------------------------------------------------------------------
  // PBI-011: Delete a habit completion
  // ---------------------------------------------------------------------------

  "DELETE /habits/{habitId}/completions/{completionId} (PBI-011)" should {

    "return 204 and subsequent GET confirms row absent" in {
      val habit      = createHabit()
      val createResp = sendPost(s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(today, None).asJson.noSpaces)
      val completion = decode[HabitCompletionResponse](createResp.body()).toOption.get

      sendDelete(s"/habits/${habit.id}/completions/${completion.id}").statusCode() shouldBe 204

      val list = decode[List[HabitCompletionResponse]](
        sendGet(s"/habits/${habit.id}/completions").body()
      ).toOption.get
      list shouldBe empty
    }

    "return 404 for a non-existent completionId" in {
      val habit = createHabit()
      sendDelete(s"/habits/${habit.id}/completions/${UUID.randomUUID()}")
        .statusCode() shouldBe 404
    }

    "return 404 when completionId belongs to a different habitId" in {
      val habit1     = createHabit("Run")
      val habit2     = createHabit("Meditate")
      val createResp = sendPost(s"/habits/${habit2.id}/completions",
        CreateHabitCompletionRequest(today, None).asJson.noSpaces)
      val completion = decode[HabitCompletionResponse](createResp.body()).toOption.get

      sendDelete(s"/habits/${habit1.id}/completions/${completion.id}").statusCode() shouldBe 404
    }
  }
}
