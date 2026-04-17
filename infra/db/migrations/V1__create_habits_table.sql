-- V1__create_habits_table.sql
-- Creates the habits table for the Habit Tracker PoC.
-- See docs/adr/ADR-002-habit-schema.md for rationale.
--
-- Rollback: DROP TABLE IF EXISTS habits;
--           DROP INDEX IF EXISTS idx_habits_active;
--           DROP INDEX IF EXISTS idx_habits_active_created_at;

CREATE TABLE habits (
    id           UUID         PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    frequency    VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    deleted_at   TIMESTAMPTZ
);

-- Partial index to keep active-habit lookups fast as soft-deleted rows accumulate.
CREATE INDEX idx_habits_active
    ON habits (id)
    WHERE deleted_at IS NULL;

-- A second partial index to accelerate GET /habits list queries ordered by creation.
CREATE INDEX idx_habits_active_created_at
    ON habits (created_at DESC)
    WHERE deleted_at IS NULL;
