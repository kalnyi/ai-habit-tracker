--liquibase formatted sql

--changeset habit-tracker:003-create-users-table
--comment: Creates the users table for the Habit Tracker PoC. See ADR-007.

CREATE TABLE users (
    id    BIGINT        PRIMARY KEY,
    name  VARCHAR(255)  NOT NULL
);

--rollback DROP TABLE IF EXISTS users;
