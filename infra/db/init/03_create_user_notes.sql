-- Run once on first container startup
-- Creates the per-user notes table for the Phase 4 multi-source RAG pipeline.
-- The vector(1536) column matches text-embedding-3-small dimensionality
-- established by EmbeddingClient (ADR-010 §2). See ADR-011 §2.
--
-- Rollback: DROP TABLE IF EXISTS user_notes; DROP INDEX IF EXISTS user_notes_user_id_idx;

CREATE TABLE IF NOT EXISTS user_notes (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    content    TEXT         NOT NULL,
    embedding  vector(1536) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS user_notes_user_id_idx
    ON user_notes (user_id);
