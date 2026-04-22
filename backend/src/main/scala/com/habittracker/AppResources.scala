package com.habittracker

import cats.effect.{Clock, IO, Resource}
import cats.syntax.semigroupk._
import com.habittracker.http.{DocsRoutes, HabitCompletionRoutes, HabitRoutes}
import com.habittracker.repository.{
  DoobieHabitCompletionRepository,
  DoobieHabitRepository,
  DoobieUserRepository,
  UserRepository
}
import com.habittracker.service.{DefaultHabitCompletionService, DefaultHabitService}
import org.http4s.HttpRoutes

final case class AppResources(
    routes: HttpRoutes[IO],
    userRepo: UserRepository
)

object AppResources {

  def make: Resource[IO, AppResources] =
    for {
      xa             <- DatabaseConfig.transactor
      userRepo        = new DoobieUserRepository(xa)
      habitRepo       = new DoobieHabitRepository(xa)
      completionRepo  = new DoobieHabitCompletionRepository(xa)
      habitService    = new DefaultHabitService(habitRepo, Clock[IO])
      completionSvc   = new DefaultHabitCompletionService(habitRepo, completionRepo, Clock[IO])
      allRoutes       = new DocsRoutes().routes <+>
                        new HabitRoutes(habitService).routes <+>
                        new HabitCompletionRoutes(completionSvc).routes
    } yield AppResources(allRoutes, userRepo)
}
