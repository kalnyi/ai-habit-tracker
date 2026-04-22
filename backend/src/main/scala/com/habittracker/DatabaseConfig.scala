package com.habittracker

import cats.effect.{IO, Resource}
import doobie.hikari.HikariTransactor

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object DatabaseConfig {

  def transactor: Resource[IO, HikariTransactor[IO]] = {
    val connectEC = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(8))
    HikariTransactor.newHikariTransactor[IO](
      "org.postgresql.Driver",
      sys.env.getOrElse("DB_URL", "jdbc:postgresql://localhost:5433/habittracker"),
      sys.env.getOrElse("DB_USER", "habituser"),
      sys.env.getOrElse("DB_PASSWORD", ""),
      connectEC
    )
  }
}
