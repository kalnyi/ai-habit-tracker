---
name: Reviewer output style preferences
description: How to format and present review findings for this project
type: feedback
---

Use the structured review format specified in the reviewer-agent system prompt:
sections for verdict, blocking issues, warnings, nits, AC table, summary.

Label every finding inline with its severity (blocking / suggestion / nit) in the
section heading — don't mix severities in a single list.

No emojis. No trailing "here is what I did" summaries. Output findings as the
final assistant message; do not write review findings to a .md file.

**Why:** The engineer reads the structured format directly and hands off blocking
issues to the Developer agent. Writing findings to a file adds an extra step.

**How to apply:** Always end with the structured review block as the primary
output. Memory and file writes (MEMORY.md updates) happen silently before the
review block.
