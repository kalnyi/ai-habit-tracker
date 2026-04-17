package com.habittracker.http.dto

final case class UpdateHabitRequest(
    name: String,
    description: Option[String],
    frequency: String
)
