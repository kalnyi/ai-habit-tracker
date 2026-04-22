package com.habittracker.repository

import cats.effect.IO
import com.habittracker.domain.User
import doobie._
import doobie.implicits._

final class DoobieUserRepository(transactor: Transactor[IO]) extends UserRepository {

  private implicit val userRead: Read[User] =
    Read[(Long, String)].map { case (id, name) => User(id, name) }

  private def findByIdQuery(id: Long): Query0[User] =
    sql"""
      SELECT id, name
      FROM users
      WHERE id = $id
    """.query[User]

  override def findById(id: Long): IO[Option[User]] =
    findByIdQuery(id).option.transact(transactor)
}
