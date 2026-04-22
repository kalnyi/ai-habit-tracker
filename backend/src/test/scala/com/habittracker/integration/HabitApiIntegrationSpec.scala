package com.habittracker.integration

import org.junit.Ignore
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import cats.effect.{Clock, IO}
import cats.effect.unsafe.IORuntime
import cats.syntax.semigroupk._
import com.comcast.ip4s._
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.{CreateHabitRequest, HabitResponse, UpdateHabitRequest}
import com.habittracker.http.{DocsRoutes, ErrorResponse, HabitRoutes}
import com.habittracker.repository.DoobieHabitRepository
import com.habittracker.service.DefaultHabitService
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.postgres.implicits._
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
import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext

// requires Docker - run manually
@Ignore
@RunWith(classOf[JUnitRunner])
class HabitApiIntegrationSpec
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

    val repo    = new DoobieHabitRepository(transactor)
    val service = new DefaultHabitService(repo, Clock[IO])
    val allRoutes = new DocsRoutes().routes <+> new HabitRoutes(service).routes

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

  override def beforeEach(): Unit = {
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

  private def sendPut(path: String, body: String): JHttpResponse[String] =
    httpClient.send(
      HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl$path"))
        .PUT(HttpRequest.BodyPublishers.ofString(body))
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

  // ---------------------------------------------------------------------------
  // POST /habits
  // ---------------------------------------------------------------------------

  "POST /habits" should {

    "return 201 with all expected fields on valid input" in {
      val body = CreateHabitRequest("Read 20 pages", Some("Non-fiction"), "daily").asJson.noSpaces
      val resp = sendPost("/habits", body)

      resp.statusCode() shouldBe 201
      val habit = decode[HabitResponse](resp.body()).toOption.get
      habit.name shouldBe "Read 20 pages"
      habit.description shouldBe Some("Non-fiction")
      habit.frequency shouldBe "daily"
      habit.id should not be null
      habit.createdAt should not be null
    }

    "return 400 with a message field when name is missing" in {
      val body = """{"description":"test","frequency":"daily"}"""
      val resp = sendPost("/habits", body)

      resp.statusCode() shouldBe 400
      val err = decode[ErrorResponse](resp.body()).toOption.get
      err.message should not be empty
    }

    "return 400 when frequency is 'monthly'" in {
      val body = CreateHabitRequest("Run", None, "monthly").asJson.noSpaces
      val resp = sendPost("/habits", body)

      resp.statusCode() shouldBe 400
      val err = decode[ErrorResponse](resp.body()).toOption.get
      err.message should not be empty
    }
  }

  // ---------------------------------------------------------------------------
  // GET /habits
  // ---------------------------------------------------------------------------

  "GET /habits" should {

    "return exactly the two active habits when one is soft-deleted (PBI-003)" in {
      sendPost("/habits", CreateHabitRequest("Active 1", None, "daily").asJson.noSpaces)
      sendPost("/habits", CreateHabitRequest("Active 2", None, "weekly").asJson.noSpaces)
      val r3 = sendPost("/habits", CreateHabitRequest("To delete", None, "daily").asJson.noSpaces)

      val idToDelete = decode[HabitResponse](r3.body()).toOption.get.id
      sendDelete(s"/habits/$idToDelete")

      val listResp = sendGet("/habits")
      listResp.statusCode() shouldBe 200
      val habits = decode[List[HabitResponse]](listResp.body()).toOption.get
      habits.length shouldBe 2
      habits.map(_.name) should contain("Active 1")
      habits.map(_.name) should contain("Active 2")
      habits.map(_.name) should not contain "To delete"
    }
  }

  // ---------------------------------------------------------------------------
  // GET /habits/{id}
  // ---------------------------------------------------------------------------

  "GET /habits/{id}" should {

    "return 200 with correct field values for an existing active habit (PBI-004)" in {
      val created =
        decode[HabitResponse](
          sendPost("/habits", CreateHabitRequest("Read", Some("Daily reading"), "daily").asJson.noSpaces).body()
        ).toOption.get

      val resp = sendGet(s"/habits/${created.id}")
      resp.statusCode() shouldBe 200
      val fetched = decode[HabitResponse](resp.body()).toOption.get
      fetched.id shouldBe created.id
      fetched.name shouldBe "Read"
      fetched.description shouldBe Some("Daily reading")
    }

    "return 404 for a random UUID that doesn't exist" in {
      sendGet(s"/habits/${UUID.randomUUID()}").statusCode() shouldBe 404
    }

    "return 400 when id is not a valid UUID" in {
      sendGet("/habits/not-a-uuid").statusCode() shouldBe 400
    }
  }

  // ---------------------------------------------------------------------------
  // PUT /habits/{id}
  // ---------------------------------------------------------------------------

  "PUT /habits/{id}" should {

    "return 200 with updated name and a strictly later updatedAt (PBI-005)" in {
      val created =
        decode[HabitResponse](
          sendPost("/habits", CreateHabitRequest("Old name", None, "daily").asJson.noSpaces).body()
        ).toOption.get

      Thread.sleep(50)

      val updateResp = sendPut(s"/habits/${created.id}",
        UpdateHabitRequest("New name", None, "weekly").asJson.noSpaces)

      updateResp.statusCode() shouldBe 200
      val updated = decode[HabitResponse](updateResp.body()).toOption.get
      updated.name shouldBe "New name"
      updated.frequency shouldBe "weekly"
      updated.updatedAt.isAfter(created.updatedAt) shouldBe true
    }

    "return 404 for a random UUID that doesn't exist" in {
      val resp = sendPut(s"/habits/${UUID.randomUUID()}",
        UpdateHabitRequest("Name", None, "daily").asJson.noSpaces)
      resp.statusCode() shouldBe 404
    }
  }

  // ---------------------------------------------------------------------------
  // DELETE /habits/{id}
  // ---------------------------------------------------------------------------

  "DELETE /habits/{id}" should {

    "return 204; GET then returns 404; row still in DB with non-null deleted_at (PBI-006)" in {
      val created =
        decode[HabitResponse](
          sendPost("/habits", CreateHabitRequest("To delete", None, "daily").asJson.noSpaces).body()
        ).toOption.get

      sendDelete(s"/habits/${created.id}").statusCode() shouldBe 204
      sendGet(s"/habits/${created.id}").statusCode() shouldBe 404

      val count =
        sql"SELECT COUNT(*) FROM habits WHERE id = ${created.id}"
          .query[Long].unique.transact(transactor).unsafeRunSync()
      count shouldBe 1L

      val deletedAt =
        sql"SELECT deleted_at FROM habits WHERE id = ${created.id}"
          .query[Option[Instant]].option.transact(transactor).unsafeRunSync()
      deletedAt.flatten should not be empty

      sendDelete(s"/habits/${created.id}").statusCode() shouldBe 404
    }

    "return 404 when deleting a non-existent ID" in {
      sendDelete(s"/habits/${UUID.randomUUID()}").statusCode() shouldBe 404
    }
  }

  // ---------------------------------------------------------------------------
  // GET /docs/openapi.json
  // ---------------------------------------------------------------------------

  "GET /docs/openapi.json" should {

    "return 200 with Content-Type containing application/json" in {
      val resp = sendGet("/docs/openapi.json")
      resp.statusCode() shouldBe 200
      resp.headers().firstValue("Content-Type").orElse("") should include("application/json")
    }
  }
}
