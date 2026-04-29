--liquibase formatted sql

--changeset habit-tracker:005-add-completed-at-to-habit-completions
--comment: Adds optional completed_at TIMESTAMPTZ for time-of-day analysis. See ADR-009.

ALTER TABLE habit_completions
    ADD COLUMN completed_at TIMESTAMPTZ NULL;

--rollback ALTER TABLE habit_completions DROP COLUMN IF EXISTS completed_at;
