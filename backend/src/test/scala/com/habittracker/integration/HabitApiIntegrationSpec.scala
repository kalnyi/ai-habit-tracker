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
import com.habittracker.http.HabitCodecs._
import com.habittracker.http.dto.{CreateHabitRequest, HabitResponse, UpdateHabitRequest}
import com.habittracker.http.{DocsRoutes, ErrorResponse, HabitRoutes}
import com.habittracker.repository.DoobieHabitRepository
import com.habittracker.service.DefaultHabitService
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

// requires Docker - run manually
@Ignore
@RunWith(classOf[JUnitRunner])
class HabitApiIntegrationSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ScalaFutures
    with FailFastCirceSupport {

  implicit val patience: PatienceConfig = PatienceConfig(timeout = 30.seconds, interval = 500.millis)
  implicit val ioRuntime: IORuntime     = IORuntime.global

  // ---------------------------------------------------------------------------
  // Infrastructure
  // ---------------------------------------------------------------------------

  private val container: PostgreSQLContainer[Nothing] =
    new PostgreSQLContainer(
      DockerImageName.parse("postgres:17-alpine")
    )

  implicit private var system: ActorSystem[Nothing]  = _
  implicit private var ec: ExecutionContext           = _
  private var transactor: HikariTransactor[IO]        = _
  private var binding: Http.ServerBinding             = _
  private var baseUrl: String                         = _
  private var docsRoutes: DocsRoutes                  = _

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

    system = ActorSystem(Behaviors.empty, "habit-api-integration-test")
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

    val repo    = new DoobieHabitRepository(transactor)
    val service = new DefaultHabitService(repo, Clock[IO])
    val routes  = new HabitRoutes(service)
    docsRoutes  = new DocsRoutes()

    binding = Http()
      .newServerAt("127.0.0.1", 0)
      .bind(routes.route ~ docsRoutes.route)
      .futureValue

    val port = binding.localAddress.getPort
    baseUrl  = s"http://127.0.0.1:$port"
  }

  override def afterAll(): Unit = {
    binding.unbind().futureValue
    system.terminate()
    container.stop()
    super.afterAll()
  }

  override def beforeEach(): Unit = {
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

  private def sendPut(path: String, body: String): HttpResponse =
    Http()
      .singleRequest(
        HttpRequest(
          method = HttpMethods.PUT,
          uri = s"$baseUrl$path",
          entity = HttpEntity(ContentTypes.`application/json`, body)
        )
      )
      .futureValue

  private def sendGet(path: String): HttpResponse =
    Http().singleRequest(HttpRequest(method = HttpMethods.GET, uri = s"$baseUrl$path")).futureValue

  private def sendDelete(path: String): HttpResponse =
    Http().singleRequest(HttpRequest(method = HttpMethods.DELETE, uri = s"$baseUrl$path")).futureValue

  private def bodyAsString(response: HttpResponse): String =
    Unmarshal(response.entity).to[String].futureValue

  // ---------------------------------------------------------------------------
  // POST /habits
  // ---------------------------------------------------------------------------

  "POST /habits" should {

    "return 201 with all expected fields on valid input" in {
      val body = CreateHabitRequest("Read 20 pages", Some("Non-fiction"), "daily").asJson.noSpaces
      val resp = sendPost("/habits", body)

      resp.status shouldBe StatusCodes.Created
      val habit = decode[HabitResponse](bodyAsString(resp)).toOption.get
      habit.name shouldBe "Read 20 pages"
      habit.description shouldBe Some("Non-fiction")
      habit.frequency shouldBe "daily"
      habit.id should not be null
      habit.createdAt should not be null
      habit.updatedAt should not be null
    }

    "return 400 with a message field when name is missing" in {
      val body = """{"description":"test","frequency":"daily"}"""
      val resp = sendPost("/habits", body)

      resp.status shouldBe StatusCodes.BadRequest
      val err = decode[ErrorResponse](bodyAsString(resp)).toOption.get
      err.message should not be empty
    }

    "return 400 when frequency is 'monthly'" in {
      val body = CreateHabitRequest("Run", None, "monthly").asJson.noSpaces
      val resp = sendPost("/habits", body)

      resp.status shouldBe StatusCodes.BadRequest
      val err = decode[ErrorResponse](bodyAsString(resp)).toOption.get
      err.message should not be empty
    }
  }

  // ---------------------------------------------------------------------------
  // GET /habits
  // ---------------------------------------------------------------------------

  "GET /habits" should {

    "return exactly the two active habits when one is soft-deleted (PBI-003)" in {
      val b2 = CreateHabitRequest("Active 2", None, "weekly").asJson.noSpaces
      val b3 = CreateHabitRequest("To delete", None, "daily").asJson.noSpaces

      sendPost("/habits", CreateHabitRequest("Active 1", None, "daily").asJson.noSpaces)
      sendPost("/habits", b2)
      val r3 = sendPost("/habits", b3)

      val idToDelete = decode[HabitResponse](bodyAsString(r3)).toOption.get.id

      sendDelete(s"/habits/$idToDelete")

      val listResp = sendGet("/habits")
      listResp.status shouldBe StatusCodes.OK
      val habits = decode[List[HabitResponse]](bodyAsString(listResp)).toOption.get
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
      val createBody = CreateHabitRequest("Read", Some("Daily reading"), "daily").asJson.noSpaces
      val created = decode[HabitResponse](bodyAsString(sendPost("/habits", createBody))).toOption.get

      val resp = sendGet(s"/habits/${created.id}")
      resp.status shouldBe StatusCodes.OK
      val fetched = decode[HabitResponse](bodyAsString(resp)).toOption.get
      fetched.id shouldBe created.id
      fetched.name shouldBe "Read"
      fetched.description shouldBe Some("Daily reading")
    }

    "return 404 for a random UUID that doesn't exist" in {
      val resp = sendGet(s"/habits/${UUID.randomUUID()}")
      resp.status shouldBe StatusCodes.NotFound
    }

    "return 400 when id is not a valid UUID" in {
      val resp = sendGet("/habits/not-a-uuid")
      resp.status shouldBe StatusCodes.BadRequest
    }
  }

  // ---------------------------------------------------------------------------
  // PUT /habits/{id}
  // ---------------------------------------------------------------------------

  "PUT /habits/{id}" should {

    "return 200 with updated name and a strictly later updatedAt (PBI-005)" in {
      val createBody = CreateHabitRequest("Old name", None, "daily").asJson.noSpaces
      val created = decode[HabitResponse](bodyAsString(sendPost("/habits", createBody))).toOption.get

      // Small sleep to ensure clock advances
      Thread.sleep(50)

      val updateBody = UpdateHabitRequest("New name", None, "weekly").asJson.noSpaces
      val updateResp = sendPut(s"/habits/${created.id}", updateBody)

      updateResp.status shouldBe StatusCodes.OK
      val updated = decode[HabitResponse](bodyAsString(updateResp)).toOption.get
      updated.name shouldBe "New name"
      updated.frequency shouldBe "weekly"
      updated.updatedAt.isAfter(created.updatedAt) shouldBe true

      // Subsequent GET reflects the new name
      val getResp = sendGet(s"/habits/${created.id}")
      val fetched = decode[HabitResponse](bodyAsString(getResp)).toOption.get
      fetched.name shouldBe "New name"
    }

    "return 404 for a random UUID that doesn't exist" in {
      val body = UpdateHabitRequest("Name", None, "daily").asJson.noSpaces
      val resp = sendPut(s"/habits/${UUID.randomUUID()}", body)

      resp.status shouldBe StatusCodes.NotFound
    }
  }

  // ---------------------------------------------------------------------------
  // DELETE /habits/{id}
  // ---------------------------------------------------------------------------

  "DELETE /habits/{id}" should {

    "return 204; GET then returns 404; row still in DB with non-null deleted_at (PBI-006)" in {
      val createBody = CreateHabitRequest("To delete", None, "daily").asJson.noSpaces
      val created = decode[HabitResponse](bodyAsString(sendPost("/habits", createBody))).toOption.get

      // First DELETE -> 204
      val deleteResp = sendDelete(s"/habits/${created.id}")
      deleteResp.status shouldBe StatusCodes.NoContent

      // GET /habits/{id} -> 404
      val getResp = sendGet(s"/habits/${created.id}")
      getResp.status shouldBe StatusCodes.NotFound

      // Direct SQL: row still present
      val count = sql"SELECT COUNT(*) FROM habits WHERE id = ${created.id}"
        .query[Long]
        .unique
        .transact(transactor)
        .unsafeRunSync()
      count shouldBe 1L

      // deleted_at is non-null (use Instant via postgres implicits)
      val deletedAt =
        sql"SELECT deleted_at FROM habits WHERE id = ${created.id}"
          .query[Option[Instant]]
          .option
          .transact(transactor)
          .unsafeRunSync()
      deletedAt.flatten should not be empty

      // Second DELETE -> 404
      val secondDelete = sendDelete(s"/habits/${created.id}")
      secondDelete.status shouldBe StatusCodes.NotFound
    }

    "return 404 when deleting a non-existent ID" in {
      val resp = sendDelete(s"/habits/${UUID.randomUUID()}")
      resp.status shouldBe StatusCodes.NotFound
    }
  }

  // ---------------------------------------------------------------------------
  // GET /docs/openapi.json
  // ---------------------------------------------------------------------------

  "GET /docs/openapi.json" should {

    "return 200 with Content-Type containing application/json" in {
      val resp = sendGet("/docs/openapi.json")
      resp.status shouldBe StatusCodes.OK
      resp.entity.contentType.toString() should include("application/json")
    }
  }
}
