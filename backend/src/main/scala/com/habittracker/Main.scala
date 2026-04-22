package com.habittracker

import cats.effect.{IO, IOApp}
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder

object Main extends IOApp.Simple {

  def run: IO[Unit] =
    AppResources.make.use { resources =>
      EmberServerBuilder
        .default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(port"8080")
        .withHttpApp(resources.routes.orNotFound)
        .build
        .useForever
    }
}
