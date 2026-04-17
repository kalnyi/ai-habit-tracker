-- Run once on first container startup
-- Enables the pgvector extension for RAG embeddings

CREATE EXTENSION IF NOT EXISTS vector;
