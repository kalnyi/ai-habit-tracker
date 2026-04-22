package com.habittracker

import cats.effect.{Clock, IO, Resource}
import cats.syntax.semigroupk._
import com.habittracker.client.AnthropicClient
import com.habittracker.http.{DocsRoutes, HabitCompletionRoutes, HabitRoutes, InsightsRoutes}
import com.habittracker.repository.{
  DoobieAnalyticsRepository,
  DoobieHabitCompletionRepository,
  DoobieHabitRepository,
  DoobieUserRepository,
  UserRepository
}
import com.habittracker.service.{
  DefaultAnalyticsService,
  DefaultHabitCompletionService,
  DefaultHabitService
}
import org.http4s.HttpRoutes

final case class AppResources(
    routes:   HttpRoutes[IO],
    userRepo: UserRepository
)

object AppResources {

  def make: Resource[IO, AppResources] =
    for {
      xa               <- DatabaseConfig.transactor
      _                <- Resource.eval(IO(AnthropicClient.API_KEY_CHECK))
      userRepo          = new DoobieUserRepository(xa)
      habitRepo         = new DoobieHabitRepository(xa)
      completionRepo    = new DoobieHabitCompletionRepository(xa)
      analyticsRepo     = new DoobieAnalyticsRepository(xa)
      habitService      = new DefaultHabitService(habitRepo, Clock[IO])
      completionSvc     = new DefaultHabitCompletionService(habitRepo, completionRepo, Clock[IO])
      analyticsService  = new DefaultAnalyticsService(habitRepo, analyticsRepo)
      allRoutes         = new DocsRoutes().routes <+>
                          new HabitRoutes(habitService).routes <+>
                          new HabitCompletionRoutes(completionSvc).routes <+>
                          new InsightsRoutes(analyticsService).routes
    } yield AppResources(allRoutes, userRepo)
}
