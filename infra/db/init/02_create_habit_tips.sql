-- Run once on first container startup
-- Creates the corpus table for pgvector cosine-similarity retrieval
-- See ADR-010.

CREATE TABLE IF NOT EXISTS habit_tips (
    id        BIGSERIAL    PRIMARY KEY,
    content   TEXT         NOT NULL,
    embedding vector(1536) NOT NULL
);

-- md5 hash index because pgvector tip strings can exceed the B-tree
-- size limit. SeedTips also performs a SELECT-then-INSERT idempotency
-- check, so this is a defensive layer.
CREATE UNIQUE INDEX IF NOT EXISTS uq_habit_tips_content
    ON habit_tips (md5(content));
