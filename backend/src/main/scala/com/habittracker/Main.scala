package com.habittracker

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import cats.effect.{Clock, IO, Resource}
import cats.effect.unsafe.IORuntime
import com.habittracker.config.AppConfig
import akka.http.scaladsl.server.Directives._
import com.habittracker.http.{DocsRoutes, HabitCompletionRoutes, HabitRoutes}
import com.habittracker.repository.{DoobieHabitCompletionRepository, DoobieHabitRepository}
import com.habittracker.service.{DefaultHabitCompletionService, DefaultHabitService}
import doobie.hikari.HikariTransactor
import org.slf4j.LoggerFactory
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Main {

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {

    // 1. Load configuration
    val config = ConfigSource.default.loadOrThrow[AppConfig]

    // 2. Create the ActorSystem (Akka HTTP runtime)
    implicit val system: ActorSystem[Nothing] =
      ActorSystem(Behaviors.empty, "habit-tracker")
    implicit val ec: ExecutionContext = system.executionContext

    // 3. Create the cats-effect IORuntime (bridge IO -> Future at route boundary)
    implicit val ioRuntime: IORuntime = IORuntime.global

    // 4. Build the HikariTransactor resource and start the server
    val transactorResource: Resource[IO, HikariTransactor[IO]] =
      HikariTransactor.newHikariTransactor[IO](
        config.database.driver,
        config.database.url,
        config.database.user,
        config.database.password,
        ec
      )

    val serverIO: IO[Unit] = transactorResource.use { transactor =>
      val repo               = new DoobieHabitRepository(transactor)
      val completionRepo     = new DoobieHabitCompletionRepository(transactor)
      val service            = new DefaultHabitService(repo, Clock[IO])
      val completionService  = new DefaultHabitCompletionService(repo, completionRepo, Clock[IO])
      val routes             = new HabitRoutes(service)
      val completionRoutes   = new HabitCompletionRoutes(completionService)
      val docsRoutes         = new DocsRoutes()

      IO.fromFuture(IO {
        Http()
          .newServerAt(config.server.host, config.server.port)
          .bind(docsRoutes.route ~ routes.route ~ completionRoutes.route)
      }).flatMap { binding =>
        log.info(s"Server started at http://${config.server.host}:${config.server.port}/")

        // Register graceful shutdown hook
        sys.addShutdownHook {
          log.info("Shutting down server...")
          binding.terminate(hardDeadline = 10.seconds).onComplete {
            case Success(_) =>
              system.terminate()
              log.info("Server stopped")
            case Failure(ex) =>
              log.error("Error during shutdown", ex)
              system.terminate()
          }
        }

        IO.never
      }
    }

    serverIO.unsafeRunSync()
  }


}
