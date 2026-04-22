--liquibase formatted sql

--changeset habit-tracker:004-add-user-id-to-habits
--comment: Seeds the default user and adds habits.user_id with FK to users(id). See ADR-007.

-- Default user seed: idempotent so re-running against an already-seeded database is a no-op.
INSERT INTO users (id, name) VALUES (1, 'default')
ON CONFLICT (id) DO NOTHING;

-- Add the column as nullable so existing rows can be backfilled.
ALTER TABLE habits ADD COLUMN user_id BIGINT;

-- Backfill every existing habit to the default user.
UPDATE habits SET user_id = 1 WHERE user_id IS NULL;

-- Now the column is fully populated; enforce NOT NULL.
ALTER TABLE habits ALTER COLUMN user_id SET NOT NULL;

-- Foreign key without cascade (matches ADR-005's FK style).
ALTER TABLE habits
    ADD CONSTRAINT fk_habits_user
    FOREIGN KEY (user_id) REFERENCES users (id);

-- Index to support listing a user's active habits ordered by creation time.
CREATE INDEX idx_habits_user_created_at
    ON habits (user_id, created_at DESC)
    WHERE deleted_at IS NULL;

--rollback DROP INDEX IF EXISTS idx_habits_user_created_at;
--rollback ALTER TABLE habits DROP CONSTRAINT IF EXISTS fk_habits_user;
--rollback ALTER TABLE habits DROP COLUMN IF EXISTS user_id;
--rollback DELETE FROM users WHERE id = 1;
