package com.habittracker.integration

import org.junit.Ignore
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshal
import cats.effect.{Clock, IO}
import cats.effect.unsafe.IORuntime
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
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import doobie.hikari.HikariTransactor
import doobie.implicits._
import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

import java.time.LocalDate
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

// requires Docker - run manually
@Ignore
@RunWith(classOf[JUnitRunner])
class HabitCompletionApiIntegrationSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ScalaFutures
    with FailFastCirceSupport {

  implicit val patience: PatienceConfig =
    PatienceConfig(timeout = 30.seconds, interval = 500.millis)
  implicit val ioRuntime: IORuntime = IORuntime.global

  // ---------------------------------------------------------------------------
  // Infrastructure
  // ---------------------------------------------------------------------------

  private val container: PostgreSQLContainer[Nothing] =
    new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))

  implicit private var system: ActorSystem[Nothing]       = _
  implicit private var ec: ExecutionContext               = _
  private var transactor: HikariTransactor[IO]            = _
  private var binding: Http.ServerBinding                 = _
  private var baseUrl: String                             = _

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

    system = ActorSystem(Behaviors.empty, "habit-completion-api-integration-test")
    ec     = system.executionContext

    transactor = HikariTransactor
      .newHikariTransactor[IO](
        "org.postgresql.Driver",
        container.getJdbcUrl,
        container.getUsername,
        container.getPassword,
        ec
      )
      .allocated
      .unsafeRunSync()
      ._1

    val habitRepo      = new DoobieHabitRepository(transactor)
    val completionRepo = new DoobieHabitCompletionRepository(transactor)
    val habitService   = new DefaultHabitService(habitRepo, Clock[IO])
    val completionSvc  = new DefaultHabitCompletionService(habitRepo, completionRepo, Clock[IO])
    val habitRoutes    = new HabitRoutes(habitService)
    val compRoutes     = new HabitCompletionRoutes(completionSvc)
    val docsRoutes     = new DocsRoutes()

    binding = Http()
      .newServerAt("127.0.0.1", 0)
      .bind(docsRoutes.route ~ habitRoutes.route ~ compRoutes.route)
      .futureValue

    baseUrl = s"http://127.0.0.1:${binding.localAddress.getPort}"
  }

  override def afterAll(): Unit = {
    binding.unbind().futureValue
    system.terminate()
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

  private def sendPost(path: String, body: String): HttpResponse =
    Http()
      .singleRequest(
        HttpRequest(
          method = HttpMethods.POST,
          uri = s"$baseUrl$path",
          entity = HttpEntity(ContentTypes.`application/json`, body)
        )
      )
      .futureValue

  private def sendGet(path: String): HttpResponse =
    Http()
      .singleRequest(HttpRequest(method = HttpMethods.GET, uri = s"$baseUrl$path"))
      .futureValue

  private def sendDelete(path: String): HttpResponse =
    Http()
      .singleRequest(HttpRequest(method = HttpMethods.DELETE, uri = s"$baseUrl$path"))
      .futureValue

  private def bodyAsString(response: HttpResponse): String =
    Unmarshal(response.entity).to[String].futureValue

  private def createHabit(name: String = "Run"): HabitResponse = {
    val body = CreateHabitRequest(name, None, "daily").asJson.noSpaces
    decode[HabitResponse](bodyAsString(sendPost("/habits", body))).toOption.get
  }

  private val today     = LocalDate.of(2026, 4, 17)
  private val yesterday = today.minusDays(1)
  private val tomorrow  = today.plusDays(1)

  // ---------------------------------------------------------------------------
  // PBI-009: Record a habit completion
  // ---------------------------------------------------------------------------

  "POST /habits/{habitId}/completions (PBI-009)" should {

    "return 201 with all expected response fields on valid input" in {
      val habit = createHabit()
      val body  = CreateHabitCompletionRequest(today, Some("Felt energised")).asJson.noSpaces

      val resp = sendPost(s"/habits/${habit.id}/completions", body)
      resp.status shouldBe StatusCodes.Created

      val completion = decode[HabitCompletionResponse](bodyAsString(resp)).toOption.get
      completion.id should not be null
      completion.habitId shouldBe habit.id
      completion.completedOn shouldBe today
      completion.note shouldBe Some("Felt energised")
      completion.createdAt should not be null
    }

    "return 404 for a non-existent habitId" in {
      val body = CreateHabitCompletionRequest(today, None).asJson.noSpaces
      val resp = sendPost(s"/habits/${UUID.randomUUID()}/completions", body)
      resp.status shouldBe StatusCodes.NotFound
    }

    "return 409 on duplicate (habitId, completedOn)" in {
      val habit = createHabit()
      val body  = CreateHabitCompletionRequest(today, None).asJson.noSpaces

      sendPost(s"/habits/${habit.id}/completions", body).status shouldBe StatusCodes.Created
      val second = sendPost(s"/habits/${habit.id}/completions", body)
      second.status shouldBe StatusCodes.Conflict
    }

    "return 400 when completedOn is not a valid date string" in {
      val habit = createHabit()
      val body  = """{"completedOn":"not-a-date"}"""
      val resp  = sendPost(s"/habits/${habit.id}/completions", body)
      resp.status shouldBe StatusCodes.BadRequest
    }

    "return 400 when habitId is not a valid UUID" in {
      val body = CreateHabitCompletionRequest(today, None).asJson.noSpaces
      val resp = sendPost("/habits/not-a-uuid/completions", body)
      resp.status shouldBe StatusCodes.BadRequest
    }
  }

  // ---------------------------------------------------------------------------
  // PBI-010: List completions for a habit
  // ---------------------------------------------------------------------------

  "GET /habits/{habitId}/completions (PBI-010)" should {

    "return completions ordered by completedOn DESC" in {
      val habit = createHabit()

      sendPost(
        s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(yesterday, None).asJson.noSpaces
      )
      sendPost(
        s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(today, None).asJson.noSpaces
      )
      sendPost(
        s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(tomorrow, None).asJson.noSpaces
      )

      val resp = sendGet(s"/habits/${habit.id}/completions")
      resp.status shouldBe StatusCodes.OK

      val list = decode[List[HabitCompletionResponse]](bodyAsString(resp)).toOption.get
      list.length shouldBe 3
      list.map(_.completedOn) shouldBe List(tomorrow, today, yesterday)
    }

    "apply from and to filters" in {
      val habit = createHabit()

      sendPost(
        s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(yesterday, None).asJson.noSpaces
      )
      sendPost(
        s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(today, None).asJson.noSpaces
      )
      sendPost(
        s"/habits/${habit.id}/completions",
        CreateHabitCompletionRequest(tomorrow, None).asJson.noSpaces
      )

      val resp = sendGet(s"/habits/${habit.id}/completions?from=$today&to=$today")
      resp.status shouldBe StatusCodes.OK

      val list = decode[List[HabitCompletionResponse]](bodyAsString(resp)).toOption.get
      list.length shouldBe 1
      list.head.completedOn shouldBe today
    }

    "return 404 for a non-existent habitId" in {
      val resp = sendGet(s"/habits/${UUID.randomUUID()}/completions")
      resp.status shouldBe StatusCodes.NotFound
    }

    "return 200 with body [] for a habit with no completions" in {
      val habit = createHabit()
      val resp  = sendGet(s"/habits/${habit.id}/completions")
      resp.status shouldBe StatusCodes.OK
      bodyAsString(resp) shouldBe "[]"
    }

    "return 400 when from is not a valid date" in {
      val habit = createHabit()
      val resp  = sendGet(s"/habits/${habit.id}/completions?from=not-a-date")
      resp.status shouldBe StatusCodes.BadRequest
    }
  }

  // ---------------------------------------------------------------------------
  // PBI-011: Delete a habit completion
  // ---------------------------------------------------------------------------

  "DELETE /habits/{habitId}/completions/{completionId} (PBI-011)" should {

    "return 204 and subsequent GET confirms row absent" in {
      val habit        = createHabit()
      val body         = CreateHabitCompletionRequest(today, None).asJson.noSpaces
      val createResp   = sendPost(s"/habits/${habit.id}/completions", body)
      val completion   = decode[HabitCompletionResponse](bodyAsString(createResp)).toOption.get

      val deleteResp = sendDelete(s"/habits/${habit.id}/completions/${completion.id}")
      deleteResp.status shouldBe StatusCodes.NoContent

      val listResp = sendGet(s"/habits/${habit.id}/completions")
      val list     = decode[List[HabitCompletionResponse]](bodyAsString(listResp)).toOption.get
      list shouldBe empty
    }

    "return 404 for a non-existent completionId" in {
      val habit = createHabit()
      val resp  = sendDelete(s"/habits/${habit.id}/completions/${UUID.randomUUID()}")
      resp.status shouldBe StatusCodes.NotFound
    }

    "return 404 when completionId belongs to a different habitId" in {
      val habit1       = createHabit("Run")
      val habit2       = createHabit("Meditate")
      val body         = CreateHabitCompletionRequest(today, None).asJson.noSpaces
      val createResp   = sendPost(s"/habits/${habit2.id}/completions", body)
      val completion   = decode[HabitCompletionResponse](bodyAsString(createResp)).toOption.get

      val resp = sendDelete(s"/habits/${habit1.id}/completions/${completion.id}")
      resp.status shouldBe StatusCodes.NotFound
    }
  }
}
