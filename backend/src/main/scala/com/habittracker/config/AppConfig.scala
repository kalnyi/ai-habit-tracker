package com.habittracker.config

final case class ServerConfig(host: String, port: Int)

final case class DatabaseConfig(
    url: String,
    user: String,
    password: String,
    driver: String
)

final case class AppConfig(server: ServerConfig, database: DatabaseConfig)
