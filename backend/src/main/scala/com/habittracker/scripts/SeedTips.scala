package com.habittracker.scripts

import cats.effect.{IO, IOApp}
import cats.syntax.all._
import com.habittracker.DatabaseConfig
import com.habittracker.client.EmbeddingClient
import com.habittracker.repository.TipRepository
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.io.Source

/** Reads habit_tips.txt from the classpath, embeds each non-blank line via
  * the OpenAI embeddings API, and inserts it into the habit_tips table.
  * Already-seeded tips are skipped (idempotent via SELECT-then-INSERT).
  *
  * Usage: ./gradlew runSeedTips
  * Requires: OPENAI_API_KEY and DB_* environment variables. */
object SeedTips extends IOApp.Simple {

  override def run: IO[Unit] =
    Slf4jLogger.create[IO].flatMap { logger =>
      DatabaseConfig.transactor.use { xa =>
        val tipRepo = new TipRepository(xa)

        val readLines: IO[List[String]] =
          IO(Source.fromResource("habit_tips.txt"))
            .bracket(src => IO(src.getLines().toList))(src => IO(src.close()))
            .map(_.filter(_.trim.nonEmpty))

        readLines.flatMap { lines =>
          val total = lines.size
          logger.info(s"Starting SeedTips: $total tips found in habit_tips.txt") *>
            lines.zipWithIndex.traverse_ { case (line, idx) =>
              tipRepo.findExistingByContent(line).flatMap {
                case Some(_) =>
                  logger.info(s"Skipped tip ${idx + 1}/$total (already exists): ${line.take(50)}")
                case None =>
                  for {
                    embedding <- EmbeddingClient.embed[IO](line)
                    _         <- tipRepo.insert(line, embedding)
                    _         <- logger.info(s"Seeded tip ${idx + 1}/$total: ${line.take(50)}")
                  } yield ()
              }
            } *> logger.info("SeedTips complete.")
        }
      }
    }
}
