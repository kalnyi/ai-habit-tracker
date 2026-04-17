--liquibase formatted sql

--changeset habit-tracker:002-create-habit-completions-table
--comment: Creates the habit_completions table for the Habit Tracker PoC. See ADR-005.

CREATE TABLE habit_completions (
    id            UUID         PRIMARY KEY,
    habit_id      UUID         NOT NULL,
    completed_on  DATE         NOT NULL,
    note          TEXT,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_habit_completions_habit_day UNIQUE (habit_id, completed_on),
    CONSTRAINT fk_habit_completions_habit
        FOREIGN KEY (habit_id) REFERENCES habits (id)
);

CREATE INDEX idx_habit_completions_habit_day_desc
    ON habit_completions (habit_id, completed_on DESC);

--rollback DROP INDEX IF EXISTS idx_habit_completions_habit_day_desc;
--rollback DROP TABLE IF EXISTS habit_completions;
