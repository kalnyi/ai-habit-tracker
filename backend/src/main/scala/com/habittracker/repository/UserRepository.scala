package com.habittracker.repository

import cats.effect.IO
import com.habittracker.domain.User

trait UserRepository {
  def findById(id: Long): IO[Option[User]]
}
