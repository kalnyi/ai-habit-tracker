package com.habittracker

import cats.effect.{Clock, IO, Resource}
import cats.syntax.semigroupk._
import com.habittracker.client.{AnthropicClient, EmbeddingClient}
import com.habittracker.http.{AnalysisRoutes, BatchCompletionRoutes, DocsRoutes, HabitCompletionRoutes, HabitRoutes, InsightsRoutes, TipsRoutes}
import com.habittracker.repository.{
  DoobieAnalyticsRepository,
  DoobieHabitCompletionRepository,
  DoobieHabitRepository,
  DoobieUserRepository,
  TipRepository,
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
      _                <- Resource.eval(IO(EmbeddingClient.API_KEY_CHECK))
      userRepo          = new DoobieUserRepository(xa)
      habitRepo         = new DoobieHabitRepository(xa)
      completionRepo    = new DoobieHabitCompletionRepository(xa)
      analyticsRepo     = new DoobieAnalyticsRepository(xa)
      tipRepo           = new TipRepository(xa)
      habitService      = new DefaultHabitService(habitRepo, Clock[IO])
      completionSvc     = new DefaultHabitCompletionService(habitRepo, completionRepo, Clock[IO])
      analyticsService  = new DefaultAnalyticsService(habitRepo, analyticsRepo)
      allRoutes         = new DocsRoutes().routes <+>
                          new InsightsRoutes(analyticsService).routes <+>
                          new AnalysisRoutes(analyticsService).routes <+>
                          new TipsRoutes(analyticsService, tipRepo).routes <+>
                          new BatchCompletionRoutes(completionSvc).routes <+>
                          new HabitRoutes(habitService).routes <+>
                          new HabitCompletionRoutes(completionSvc).routes
    } yield AppResources(allRoutes, userRepo)
}
