--liquibase formatted sql

--changeset habit-tracker:001-create-habits-table
--comment: Creates the habits table for the Habit Tracker PoC. See ADR-002.

CREATE TABLE habits (
    id           UUID         PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    frequency    VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    deleted_at   TIMESTAMPTZ
);

CREATE INDEX idx_habits_active
    ON habits (id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_habits_active_created_at
    ON habits (created_at DESC)
    WHERE deleted_at IS NULL;

--rollback DROP INDEX IF EXISTS idx_habits_active_created_at;
--rollback DROP INDEX IF EXISTS idx_habits_active;
--rollback DROP TABLE IF EXISTS habits;
