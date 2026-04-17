package com.habittracker.http.dto

final case class CreateHabitRequest(
    name: String,
    description: Option[String],
    frequency: String
)
