package com.habittracker.domain

sealed trait Frequency

object Frequency {
  case object Daily extends Frequency
  case object Weekly extends Frequency
  final case class Custom(value: String) extends Frequency

  def parse(s: String): Either[String, Frequency] =
    s.trim.toLowerCase match {
      case "daily"  => Right(Daily)
      case "weekly" => Right(Weekly)
      case other if other.isBlank =>
        Left("frequency must not be blank")
      case other =>
        Left(s"unknown frequency '$other'; accepted values are 'daily' and 'weekly'")
    }

  def asString(f: Frequency): String = f match {
    case Daily        => "daily"
    case Weekly       => "weekly"
    case Custom(v)    => v
  }
}
