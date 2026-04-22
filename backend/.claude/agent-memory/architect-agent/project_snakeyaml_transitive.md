---
name: SnakeYAML already on the classpath via liquibase-core
description: liquibase-core 4.29.2 pulls in org.yaml:snakeyaml transitively; no explicit YAML dependency needed
type: project
---

`liquibase-core:4.29.2` (chosen in ADR-003 as the schema-migration tool) pulls
in `org.yaml:snakeyaml` as a transitive dependency. The Liquibase XML/YAML
changelog parser uses it.

**Why:** This matters for any future feature that needs YAML parsing
(OpenAPI specs, config snippets, test fixtures). Plans should not propose a
`circe-yaml` dependency or any other YAML library before checking whether
SnakeYAML is already reachable — adding another YAML lib is duplicative.

**How to apply:** When a plan needs YAML → JSON conversion or YAML parsing,
default to using SnakeYAML directly with a small manual walker that emits
`io.circe.Json`. Only introduce a dedicated YAML library if the use case
genuinely needs typed decoding (which is rare — OpenAPI specs are
effectively dynamic JSON trees).
