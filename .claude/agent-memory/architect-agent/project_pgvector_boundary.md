---
name: pgvector setup boundary
description: pgvector extension and corpus tables live in Docker init scripts, never in Liquibase
type: project
---

pgvector setup (CREATE EXTENSION vector + corpus tables like habit_tips,
future user_notes) lives in infra/db/init/*.sql and is mounted into the
Postgres container via docker-compose.yaml. Liquibase changesets manage
application schema only.

**Why:** CREATE EXTENSION vector requires superuser-equivalent privileges
that the application's habituser role does not have; the pgvector/pgvector
image runs init scripts as the bootstrap superuser. Corpus tables are
infrastructure tied to the embedding model choice, not feature-driven
schema evolution. Engineer chose this boundary in ADR-010.

**How to apply:** When a future PBI adds a new embedding-backed table
(e.g. user_notes in Phase 4), create infra/db/init/0N_create_X.sql, do
not add a Liquibase changeset. The next free Liquibase slot (006 as of
Phase 3) stays reserved for genuine schema changes to habits or
habit_completions.
