---
name: Liquibase Gradle plugin bootstrap requirement
description: org.liquibase.gradle v3 throws NoClassDefFoundError liquibase/Scope at plugin-apply time unless liquibase-core is on the buildscript classpath
type: project
---

The `org.liquibase.gradle` plugin version 3.0.1 calls `ArgumentBuilder.initializeGlobalArguments` during `LiquibasePlugin.apply()`, which instantiates `liquibase.Scope` immediately. The `liquibaseRuntime` configuration is only available at task-execution time, not at plugin-apply time.

**Why:** This causes a `NoClassDefFoundError: liquibase/Scope` that fails the entire Gradle build before any compilation happens. Adding `liquibase-core` only to `liquibaseRuntime` is insufficient.

**How to apply:** Always add a `buildscript { dependencies { classpath 'org.liquibase:liquibase-core:<version>' } }` block at the top of `build.gradle` whenever using the Liquibase Gradle plugin v3. The version must match the one in `ext.liquibaseVersion`. This is in addition to (not instead of) the `liquibaseRuntime` entries.
