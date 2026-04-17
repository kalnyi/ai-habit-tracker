package com.habittracker

import cats.{Applicative, Monad}
import cats.effect.{Clock, IO}

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

object TestClocks {

  /** Returns a fake [[Clock]] whose time starts at `startEpochMs` and advances
    * by 1 000 ms on every `monotonic` or `realTime` call. Thread-safe: uses an
    * [[AtomicLong]] rather than a `@volatile var` to avoid non-atomic
    * read-modify-write races.
    */
  def makeFakeClock(startEpochMs: Long): Clock[IO] = new Clock[IO] {
    private val current = new AtomicLong(startEpochMs)

    override def applicative: Applicative[IO] = implicitly[Monad[IO]]

    override def monotonic: IO[FiniteDuration] =
      IO(FiniteDuration(current.addAndGet(1000), MILLISECONDS))

    override def realTime: IO[FiniteDuration] =
      IO(FiniteDuration(current.addAndGet(1000), MILLISECONDS))
  }
}
