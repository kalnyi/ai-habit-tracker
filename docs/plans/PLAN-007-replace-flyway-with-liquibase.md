# PLAN-007: Replace Flyway with Liquibase

## PBI reference
Change request (no numbered PBI): Flyway Gradle plugin 10.17.0 is incompatible
with Gradle 9.4.1, which blocks local development and CI. Swap the entire
Flyway tool chain for Liquibase.

## Summary
Remove the Flyway Gradle plugin and `flyway-core` / `flyway-database-postgresql`
runtime dependencies, and replace them with the Liquibase Gradle plugin
(`org.liquibase.gradle` 3.0.1) plus `liquibase-core` 4.29.2. Convert the
single existing migration `infra/db/migrations/V1__create_habits_table.sql`
into a Liquibase formatted-SQL changeset under `infra/db/changelog/` driven
by an XML master changelog, and update `Main.scala` and two Testcontainers
specs to invoke the Liquibase Java API instead of `Flyway.configure()`. Ship
an ADR (ADR-003) recording the decision.

## Affected files

| File | Change type | Description |
|------|-------------|-------------|
| `backend/build.gradle` | Modify | Replace `id 'org.flywaydb.flyway' version '10.17.0'` with `id 'org.liquibase.gradle' version '3.0.1'`; remove `flyway-core` / `flyway-database-postgresql` implementation deps and the `flywayMigration` custom configuration; add `implementation 'org.liquibase:liquibase-core:4.29.2'`; add a `liquibaseRuntime` configuration with `liquibase-core`, `liquibase-picocli`, and the Postgres JDBC driver (required by the plugin); replace the `flyway { ... }` block with a `liquibase { ... }` block pointing at the new changelog path. Remove the `ext.flywayVersion` entry. |
| `backend/src/main/scala/com/habittracker/Main.scala` | Modify | Replace the `import org.flywaydb.core.Flyway` import and the `runMigrations` helper with a Liquibase-based equivalent that uses `liquibase.Liquibase`, `liquibase.database.jvm.JdbcConnection`, `liquibase.resource.ClassLoaderResourceAccessor`, and `DriverManager.getConnection(...)`. |
| `backend/src/test/scala/com/habittracker/repository/DoobieHabitRepositorySpec.scala` | Modify | Replace the `Flyway.configure()...migrate()` block inside `beforeAll` with a Liquibase invocation pointing at the new changelog on the filesystem (`DirectoryResourceAccessor` rooted at `../../infra/db/changelog`). |
| `backend/src/test/scala/com/habittracker/integration/HabitApiIntegrationSpec.scala` | Modify | Same Flyway → Liquibase swap as the repository spec. |
| `infra/db/changelog/db.changelog-master.xml` | Create | New XML master changelog with a single `<include file="changesets/001-create-habits-table.sql" relativeToChangelogFile="true"/>` entry. |
| `infra/db/changelog/changesets/001-create-habits-table.sql` | Create | Liquibase formatted-SQL changeset carrying the exact DDL from the current V1 migration (table + two partial indexes), prefixed by the required header lines. |
| `infra/db/migrations/V1__create_habits_table.sql` | Delete | Replaced by the changeset above. Safe to delete — no deployed environment has applied this file. |
| `infra/db/migrations/` | Delete directory | After the V1 file is removed, delete the now-empty directory to avoid a second source of truth. Leave `infra/db/init/01_enable_vector.sql` untouched (that is the Docker init script, not a migration). |
| `README.md` | Modify | Replace the `gradle flywayMigrate` instruction in section "3. Run database migrations" with `gradle update`; update the "Persistence" row in the tech-stack table from `Flyway` to `Liquibase`; update the project-layout tree to rename `db/migrations/  Flyway versioned migrations` → `db/changelog/    Liquibase master changelog + changesets`. |
| `docs/adr/ADR-003-migration-tool-liquibase.md` | Create | Records the decision to swap Flyway for Liquibase. (Already drafted alongside this plan.) |

## New components

None at the Scala code level. At the build/configuration level:

- **`org.liquibase.gradle` plugin** (3.0.1) — introduces Gradle tasks `update`,
  `status`, `rollback`, `validate`, `tag`, `rollbackSQL`, `updateSQL`. The
  developer workflow uses `gradle update`.
- **`liquibaseRuntime` Gradle configuration** — classpath the plugin uses to
  resolve Liquibase itself, the CLI entry point, and the Postgres JDBC driver.
  Members: `org.liquibase:liquibase-core:4.29.2`,
  `org.liquibase:liquibase-picocli:4.29.2`,
  `info.picocli:picocli:4.7.5` (required by `liquibase-picocli` on Gradle 9),
  `org.postgresql:postgresql:42.7.3`.
- **Master changelog file** `infra/db/changelog/db.changelog-master.xml` — the
  single entry point Liquibase reads. New changesets are added by appending
  `<include/>` lines.
- **Changeset directory** `infra/db/changelog/changesets/` — holds one
  formatted-SQL file per changeset. Naming convention
  `NNN-<short-slug>.sql` (e.g. `001-create-habits-table.sql`).

## API contract

None. This change is pure tooling — no REST endpoints added, modified, or
removed.

## Database changes

The DDL applied to the database is **identical** to what Flyway would have
applied: the existing `habits` table plus its two partial indexes. Only the
file layout, headers, and tracking table change.

**New file — `infra/db/changelog/db.changelog-master.xml`:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <include file="changesets/001-create-habits-table.sql" relativeToChangelogFile="true"/>

</databaseChangeLog>
```

**New file — `infra/db/changelog/changesets/001-create-habits-table.sql`:**

```sql
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
```

Notes on the SQL above:
- The `--liquibase formatted sql` header is required; Liquibase rejects the
  file as "no changesets" without it.
- The `--changeset <author>:<id>` line is the unit of tracking. `author` is
  cosmetic here (`habit-tracker`); `id` must be stable forever once applied.
- `--rollback` lines supply a rollback script for the `gradle rollback` CLI
  (optional, but cheap to include and good hygiene).

**Tracking tables.** Liquibase creates `DATABASECHANGELOG` and
`DATABASECHANGELOGLOCK` automatically on first run. No Flyway
`flyway_schema_history` migration is needed — there is no data to preserve.

## LLM integration

Not touched by this change. No prompt files, RAG flows, or Anthropic-API code
is affected.

## Code changes — `Main.scala` replacement helper

Replace this block:

```scala
import org.flywaydb.core.Flyway

private def runMigrations(config: AppConfig): Unit = {
  log.info("Running Flyway migrations...")
  Flyway
    .configure()
    .dataSource(config.database.url, config.database.user, config.database.password)
    .locations("classpath:db/migrations", "filesystem:../infra/db/migrations")
    .load()
    .migrate()
  log.info("Flyway migrations completed")
}
```

with:

```scala
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor

import java.sql.DriverManager

private def runMigrations(config: AppConfig): Unit = {
  log.info("Running Liquibase migrations...")
  val jdbcConn = DriverManager.getConnection(
    config.database.url, config.database.user, config.database.password
  )
  try {
    val database = DatabaseFactory.getInstance()
      .findCorrectDatabaseImplementation(new JdbcConnection(jdbcConn))
    val liquibase = new Liquibase(
      "db/changelog/db.changelog-master.xml",
      new ClassLoaderResourceAccessor(),
      database
    )
    liquibase.update("")
  } finally {
    jdbcConn.close()
  }
  log.info("Liquibase migrations completed")
}
```

Resource-loading rule: the `ClassLoaderResourceAccessor` resolves the path
against the JVM classpath. The runtime expects
`src/main/resources/db/changelog/...` or, for deployed artefacts, the same
path inside the classpath root. Because the source of truth lives under
`infra/db/changelog/`, the build must copy those files onto the classpath —
add to `backend/build.gradle`:

```groovy
sourceSets.main.resources.srcDirs += file('../infra/db/changelog')
```

and have it copied into a `db/changelog/` classpath prefix by placing the
files under `../infra/db/changelog/db/changelog/...`, **or** (preferred, less
directory nesting) add an explicit `processResources` rule:

```groovy
processResources {
    from('../infra/db/changelog') {
        into 'db/changelog'
    }
}
```

The `processResources` approach is the recommended one — it keeps the
`infra/db/changelog/` layout clean (no `db/changelog/` shim directory) and
is explicit about the copy step. The Developer should implement this variant.

## Code changes — Gradle build

Remove from `backend/build.gradle`:

```groovy
id 'org.flywaydb.flyway' version '10.17.0'
// ...
flywayVersion = '10.17.0'
// ...
implementation "org.flywaydb:flyway-core:${flywayVersion}"
implementation "org.flywaydb:flyway-database-postgresql:${flywayVersion}"
// ...
configurations { flywayMigration }
// ...
flywayMigration "org.flywaydb:flyway-database-postgresql:${flywayVersion}"
flywayMigration 'org.postgresql:postgresql:42.7.3'
// ...
flyway {
    url      = 'jdbc:postgresql://localhost:5432/habittracker'
    user     = System.getenv('DB_USER') ?: 'habituser'
    password = System.getenv('DB_PASSWORD') ?: ''
    locations = ['filesystem:../infra/db/migrations']
    cleanDisabled = false
    configurations = ['flywayMigration']
}
```

Add in their place:

```groovy
plugins {
    id 'scala'
    id 'application'
    id 'org.liquibase.gradle' version '3.0.1'
}

// ... inside ext { } remove flywayVersion, add:
liquibaseVersion = '4.29.2'

dependencies {
    // ... existing ...

    // Liquibase (schema migrations) — application runtime
    implementation "org.liquibase:liquibase-core:${liquibaseVersion}"

    // Liquibase CLI classpath — used by the gradle update / rollback / status tasks
    liquibaseRuntime "org.liquibase:liquibase-core:${liquibaseVersion}"
    liquibaseRuntime "org.liquibase:liquibase-picocli:${liquibaseVersion}"
    liquibaseRuntime 'info.picocli:picocli:4.7.5'
    liquibaseRuntime 'org.postgresql:postgresql:42.7.3'
    liquibaseRuntime "org.scala-lang:scala-library:${scalaVersion}"
}

liquibase {
    activities {
        main {
            changelogFile 'db.changelog-master.xml'
            searchPath    '../infra/db/changelog'
            url           'jdbc:postgresql://localhost:5432/habittracker'
            username      System.getenv('DB_USER') ?: 'habituser'
            password      System.getenv('DB_PASSWORD') ?: ''
            driver        'org.postgresql.Driver'
        }
        runList = 'main'
    }
}

processResources {
    from('../infra/db/changelog') {
        into 'db/changelog'
    }
}
```

### New dependencies — explicit call-out (per CLAUDE.md)

This plan introduces the following new dependencies. **Engineer must approve
before the Developer agent starts:**

1. **`org.liquibase.gradle` Gradle plugin, version 3.0.1** — provides the
   `gradle update`, `gradle status`, `gradle rollback` tasks. Replaces the
   `org.flywaydb.flyway` plugin.
2. **`org.liquibase:liquibase-core:4.29.2`** — programmatic migration API.
   Replaces `org.flywaydb:flyway-core`.
3. **`org.liquibase:liquibase-picocli:4.29.2`** — Liquibase's CLI entry
   point; required on the Gradle plugin's runtime classpath.
4. **`info.picocli:picocli:4.7.5`** — transitive CLI dependency of
   `liquibase-picocli`; pinned explicitly because Gradle 9's strict
   resolution sometimes fails to resolve the transitive version.

Dependencies **removed** (no approval needed; just FYI):
- `org.flywaydb.flyway` Gradle plugin 10.17.0
- `org.flywaydb:flyway-core` 10.17.0
- `org.flywaydb:flyway-database-postgresql` 10.17.0

## Test plan

The existing Testcontainers specs are `@Ignore`d by default, so their tests
are not part of the normal CI run. The conversion must still ensure both
specs **compile cleanly** and **run correctly when un-ignored**, so the
Developer must:

1. **Compile guarantee (automated).** `gradle compileTestScala -PskipDockerTests`
   must succeed. The two test files are the only places the Flyway imports
   appear outside `Main.scala`; removing them must leave no dangling
   references.
2. **Manual un-ignore run (one-off verification).** The Developer, when
   running against local Docker, temporarily removes the `@Ignore` annotation
   on `DoobieHabitRepositorySpec` and runs `gradle test`. All existing tests
   should pass exactly as they did under Flyway — the DDL is unchanged.
   Re-apply `@Ignore` before committing.
3. **`gradle update` smoke test.** With Docker Compose up and the DB empty,
   run `gradle update`. Expect: Liquibase creates `DATABASECHANGELOG`,
   applies changeset `habit-tracker:001-create-habits-table`, and reports
   "1 changeset(s) applied". Re-running must report "0 changeset(s) applied"
   (idempotency check).
4. **Startup migration smoke test.** Drop the `habits` table and the
   Liquibase tracking tables from a local DB, run `gradle run`, hit
   `POST /habits` with a trivial body. Expect: server starts, logs
   "Running Liquibase migrations... Liquibase migrations completed", and
   the POST returns 201. This exercises `Main.runMigrations` end-to-end.

No new automated tests are required by this change — migration tooling is
verified by the act of running the suite (which now needs the migrations to
succeed to get as far as setting up the schema).

### Test-spec Liquibase invocation template

Both specs use the same pattern inside `beforeAll`:

```scala
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.DirectoryResourceAccessor

import java.nio.file.Paths
import java.sql.DriverManager

// Replace the Flyway block with:
val jdbcConn = DriverManager.getConnection(
  container.getJdbcUrl, container.getUsername, container.getPassword
)
try {
  val database = DatabaseFactory.getInstance()
    .findCorrectDatabaseImplementation(new JdbcConnection(jdbcConn))
  val accessor = new DirectoryResourceAccessor(
    Paths.get("../../infra/db/changelog").toAbsolutePath.normalize
  )
  val liquibase = new Liquibase("db.changelog-master.xml", accessor, database)
  liquibase.update("")
} finally {
  jdbcConn.close()
}
```

Rationale for `DirectoryResourceAccessor` in tests (instead of the
`ClassLoaderResourceAccessor` used at runtime): the tests run from the
Gradle project directory, and the changelog is reached by a relative path —
same shape as the old `"filesystem:../../infra/db/migrations"` location.
Using `ClassLoaderResourceAccessor` in tests too would work, but only if the
`processResources` copy ran first; a directory accessor sidesteps that
ordering concern for tests.

## ADRs required

- **ADR-003 — Schema migration tool: Liquibase (replaces Flyway).** Drafted
  alongside this plan at `docs/adr/ADR-003-migration-tool-liquibase.md`.

## Open questions

1. **Changeset author string.** The plan proposes `habit-tracker` as the
   `author` portion of the `--changeset <author>:<id>` marker. Alternatives:
   the engineer's handle (`alex.kalnij`), the agent name (`architect-agent`),
   or an impersonal `system`. Engineer to confirm. The value is cosmetic but
   becomes part of the tracking-table row identity (`AUTHOR` column) and
   cannot be changed after the changeset is applied in any environment.
2. **Changeset numbering scheme.** The plan uses a 3-digit, zero-padded
   sequence (`001-`, `002-`, ...) inside `changesets/`. Flyway's
   `V1__`, `V2__` convention is an alternative. Both work; three-digit
   zero-padding sorts cleanly up to 999 changesets which is ample for a
   PoC. Engineer to confirm preference.
3. **Retention of the old history table.** The plan assumes no dev
   environment has a populated `flyway_schema_history` worth keeping. If any
   environment does, dropping it (`DROP TABLE flyway_schema_history;`) is a
   manual one-liner before first `gradle update`. Engineer to confirm no
   such environment exists.
4. **Liquibase Pro features.** Version 4.29.2 is the OSS build. Nothing in
   this plan depends on Liquibase Pro. Noted for completeness.

---

This technical plan is ready for your review. Please approve or request
changes before I hand off to the Developer agent.
