# ADR-003: Schema migration tool — Liquibase (replaces Flyway)

## Status
Accepted — supersedes the Flyway choice implicit in the initial build setup.

## Context
The Habit Tracker backend uses Gradle 9.4.1 (system Gradle; no wrapper pinned in
`backend/gradlew` for this version). The project was bootstrapped with Flyway
for schema migrations, using:

- the Flyway Gradle plugin `org.flywaydb.flyway` 10.17.0 for the
  `gradle flywayMigrate` developer workflow, and
- `flyway-core` / `flyway-database-postgresql` 10.17.0 at runtime, invoked
  programmatically from `Main.scala` and from two Testcontainers-based test
  specs.

The Flyway Gradle plugin 10.17.0 is **incompatible with Gradle 9.x**. It
targets older Gradle APIs that have been removed in Gradle 9 (e.g. the pre-9
`Project.convention` / `TaskContainer` surface that the plugin reaches into to
register its tasks). Configuring the project with Gradle 9.4.1 fails before
any migration task can run, which blocks local development and CI. No Flyway
Gradle plugin release compatible with Gradle 9.x exists as of 2026-04-17.

Three remediation options were considered:

1. **Downgrade to Gradle 8.x.** Keeps Flyway but regresses the build tool
   version. Rejected — the rest of the toolchain (JDK, Scala plugin, test
   tasks) already works against Gradle 9.4.1 and we do not want to hold the
   build back for one plugin.
2. **Drop the Flyway Gradle plugin and keep `flyway-core` only.** Run
   migrations exclusively from `Main.scala` on app startup; provide no
   `gradle flywayMigrate` developer command. Viable but leaves the CLI-only
   migration workflow (e.g. a DBA running migrations without booting the app,
   or CI running migrations before integration tests in a separate step) with
   no first-class story. Custom Gradle tasks would have to be written by hand
   to replicate what the plugin provided.
3. **Replace Flyway with Liquibase.** Liquibase publishes a Gradle plugin
   (`org.liquibase.gradle`) that supports Gradle 9.x, plus a stable Java API
   (`liquibase.Liquibase`) that can be invoked programmatically from
   application code and tests the same way Flyway was.

We chose option 3.

### Selection criteria
- **Gradle 9.x compatibility** for the CLI workflow (must work today, with
  current plugin releases).
- **Programmatic Java API** for application-startup migration and for
  Testcontainers test specs.
- **SQL-first changelogs** so existing `V1__create_habits_table.sql` content
  can be reused without rewriting into XML/YAML. Liquibase supports SQL
  changelogs via the `--sql` formatted-SQL convention.
- **No lock-in beyond migration tooling** — migrations are the only integration
  point; the rest of the stack (Doobie, Postgres, Scala) is unaffected.

### Alternatives considered (outside Flyway / Liquibase)
- **MyBatis Migrations** — small footprint but no first-party Gradle plugin,
  weaker PostgreSQL-specific tooling.
- **`jOOQ` migrations / `flyway-ng` forks** — too immature for a PoC that
  needs a predictable tool.
- **Hand-rolled `psql` scripts** — rejected; we want a tracked history table
  and idempotent `migrate` semantics.

## Decision
Replace Flyway with **Liquibase** as the sole schema migration tool. Concretely:

- Apply the **Liquibase Gradle plugin** `org.liquibase.gradle` (version 3.0.1,
  confirmed compatible with Gradle 9.x) to provide the `gradle update` CLI
  task (the Liquibase equivalent of `gradle flywayMigrate`).
- Use **`liquibase-core` 4.29.2** (current stable 4.x line) as the runtime
  dependency for programmatic migration from `Main.scala` and test specs.
- Keep migration files on disk as **Liquibase formatted-SQL changelogs**
  (`*.sql` files with a `--liquibase formatted sql` header and
  `--changeset <author>:<id>` markers). This preserves the existing raw SQL
  from `V1__create_habits_table.sql` with only header lines added on top.
- Use a **master changelog** `infra/db/changelog/db.changelog-master.xml` that
  `<include file="..."/>`s each SQL changelog in order. The master changelog
  is the single entry point Liquibase loads; individual changesets live in
  their own files so future migrations are additive.
- **Location on disk:** relocate migrations to
  `infra/db/changelog/` (master changelog + `changesets/*.sql`) and **delete**
  the old `infra/db/migrations/` directory once the conversion is complete.
  Keeping the old path would create two conflicting sources of truth and
  invite confusion; a clean rename is simpler.
- **At runtime** `Main.scala` constructs a `liquibase.Liquibase` directly with
  a `JdbcConnection` wrapping a raw `java.sql.Connection` and calls
  `.update("")` (the no-context form). This mirrors the previous
  `Flyway.configure().dataSource(...).load().migrate()` flow.
- **Tracking table.** Liquibase maintains `DATABASECHANGELOG` and
  `DATABASECHANGELOGLOCK`. These replace Flyway's `flyway_schema_history`.
  Because there is no production data to preserve (the PoC has never deployed
  anywhere permanent), **no cross-tool migration of the history table is
  needed** — any dev or CI database is torn down and recreated with Liquibase
  from scratch.

### New dependencies introduced
All explicitly flagged per CLAUDE.md:

| Dependency | Version | Purpose |
|---|---|---|
| `org.liquibase.gradle` (plugin) | 3.0.1 | Provides `gradle update`, `gradle rollback`, `gradle status` CLI tasks on Gradle 9.x |
| `org.liquibase:liquibase-core` | 4.29.2 | Programmatic migration API for `Main.scala` and test specs |

### Dependencies removed
| Dependency | Previously used for |
|---|---|
| `org.flywaydb.flyway` (plugin) 10.17.0 | Gradle CLI migration (incompatible with Gradle 9.x) |
| `org.flywaydb:flyway-core` 10.17.0 | Programmatic migration at runtime |
| `org.flywaydb:flyway-database-postgresql` 10.17.0 | Postgres dialect for Flyway |

## Consequences

**Easier:**
- Unblocks Gradle 9.4.1 — `gradle update` works end-to-end.
- Separate changelog files per changeset make future migrations additive and
  easy to review one-by-one in PRs, closer to Flyway's `V{n}__` convention.
- Liquibase's `rollback` and `status` CLI tasks are available for free, which
  Flyway did not provide on this project's setup.
- The `DATABASECHANGELOGLOCK` row gives us a built-in concurrency lock if
  migrations ever run from two processes simultaneously (e.g. two app
  instances starting in parallel).

**Harder / trade-offs:**
- Requires adding a **master changelog** XML file — a small amount of
  configuration that Flyway did not need. Acceptable: the file is static and
  only grows by one `<include/>` per migration.
- Each SQL migration file must carry a **`--liquibase formatted sql`** header
  and a **`--changeset <author>:<id>`** marker. A developer forgetting these
  headers produces a silent no-op migration. Mitigation: the convention is
  documented in CLAUDE.md and the changeset file template is included in the
  repo.
- Liquibase's **`DATABASECHANGELOG`** schema is different from Flyway's
  `flyway_schema_history`. Any ad-hoc SQL or tooling that read the Flyway
  table (none in this project today) would have to be updated. Noted for
  completeness; no action required.
- Slightly larger dependency (`liquibase-core` pulls in SnakeYAML and a few
  other transitive libs) — acceptable for a PoC.

**Locked in:**
- Migration format is **Liquibase formatted SQL**. All future migrations
  follow this convention.
- The `--changeset` `id` identity becomes part of the tracking table's
  primary key. Renaming a changeset id after it has been applied to any
  environment is a migration hazard — new changesets must get fresh ids.
- Migrations live at `infra/db/changelog/`; the `infra/db/migrations/` path
  is retired.
