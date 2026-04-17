# Habit Tracker — Claude Code Context

## Project overview

AI-enhanced Habit Tracker PoC, built as part of an ASDLC upskilling program.
The project has two distinct layers:

- **Layer 1 — ASDLC agent swarm**: BA, Architect, Developer, and Reviewer agents
  collaborate to build the application under engineer supervision.
- **Layer 2 — Habit Tracker app**: the application itself, which uses LLM calls
  and RAG internally for personalised tips, habit analysis, and pattern detection.

The goal is to learn AI-assisted software development workflows, not to ship a
production product. Prioritise clarity and learnability over premature optimisation.

---

## Repository structure

```
habit-tracker/
├── CLAUDE.md                  # this file
├── .claude/
│   ├── agents/                # sub-agent definitions (BA, Architect, Developer, Reviewer)
│   └── commands/              # custom slash commands
├── backend/                   # Scala 2.13 + Gradle REST service
│   ├── build.gradle
│   ├── src/main/scala/
│   └── src/test/scala/
├── frontend/                  # TypeScript frontend (framework TBD)
│   ├── package.json
│   └── src/
├── infra/                     # Docker Compose, DB migrations, pgvector setup
│   ├── docker-compose.yml
│   └── db/migrations/
└── docs/                      # ADRs, agent prompts, design notes
    └── adr/
```

---

## Tech stack

| Layer | Technology |
|---|---|
| Backend language | Scala 2.13 |
| Build tool | Gradle (with Scala plugin) |
| Backend framework | http4s or Play Framework (TBD in first ADR) |
| Frontend language | TypeScript |
| Frontend framework | TBD — React or Next.js, decided before first frontend PBI |
| Primary database | PostgreSQL |
| Vector store | pgvector (extension on same PostgreSQL instance) |
| LLM | Claude via Anthropic API |
| CI/CD | GitHub Actions |
| Runtime | Windows / PowerShell (local dev), Linux containers in CI |

---

## Agent swarm roles

This project uses a multi-agent pipeline. Each agent has a dedicated definition
file under `.claude/agents/`. Agents hand off in sequence; the engineer approves
each handoff gate before the next agent proceeds.

### BA agent (`ba-agent`)
- Input: rough feature idea or plain-language requirement
- Output: one or more PBIs in the format defined in `docs/pbi-template.md`
- Approval gate: engineer reviews and accepts PBIs before architect proceeds

### Architect agent (`architect-agent`)
- Input: approved PBI(s)
- Output: technical plan — affected files, new components, API contract, DB schema changes
- Must produce an ADR for any decision that affects the overall architecture
- Approval gate: engineer reviews tech plan before developer proceeds

### Developer agent (`developer-agent`)
- Input: approved technical plan
- Output: working code, unit tests, updated migrations if needed
- Must not introduce new dependencies without noting them explicitly
- Must run `./gradlew test` (backend) and `npm test` (frontend) before marking done

### Reviewer agent (`reviewer-agent`)
- Input: diff or PR from developer agent
- Output: review comments with severity labels (blocking / suggestion / nit)
- Checks: correctness, test coverage, adherence to conventions below, no secrets committed
- Integrated into GitHub Actions — runs on every PR automatically

---

## Coding conventions

### Scala backend
- Scala 2.13, functional style preferred — use `cats-effect` / `IO` for effects
- No `var`, no mutable state outside explicitly marked boundaries
- All public methods must have explicit return types
- Error handling via `Either[AppError, A]` — no raw exceptions in business logic
- Use `case class` for domain models, keep them in `domain/` package
- Test with ScalaTest + ScalaCheck for property tests where appropriate
- Format with `scalafmt` before committing — config in `.scalafmt.conf`

### TypeScript frontend
- Strict mode enabled (`"strict": true` in tsconfig)
- Functional components only — no class components
- `interface` over `type` for object shapes
- `async/await` only — no raw `.then()` chains
- All API calls go through a typed client in `src/api/`
- Format with Prettier, lint with ESLint before committing

### General
- Commit messages follow Conventional Commits: `feat:`, `fix:`, `chore:`, `docs:`
- No secrets or API keys in source — use environment variables
- Every new endpoint needs a corresponding integration test
- ADR required for: framework choices, schema decisions, LLM prompt strategy changes

---

## LLM integration (Layer 2 app)

The Habit Tracker app makes LLM calls internally. These are distinct from the
agent swarm — they are application features, not development tooling.

### LLM use cases
1. **Daily tip**: given a habit + retrieved context from pgvector, generate a
   personalised tip for today. Always RAG-augmented — never a cold prompt.
2. **Pattern analysis**: weekly summary of habit completion patterns, correlations
   between habits, and risk flags (e.g. consistent failures on specific days).
3. **Streak risk detection**: proactive alert when historical data suggests
   a habit is at risk of being broken.

### RAG setup
- User's habit history, completion records, and free-text notes are embedded
  and stored in pgvector.
- Retrieval happens at tip-generation time: fetch top-k relevant records,
  inject into the prompt as context.
- Embedding model: `text-embedding-3-small` via Anthropic-compatible endpoint
  or OpenAI SDK (TBD in ADR).

### Prompt files
- All system prompts live in `backend/src/main/resources/prompts/`
- Prompts are versioned — filename includes a version suffix, e.g. `daily-tip-v1.txt`
- Any change to a prompt requires a new version file and an entry in `docs/prompt-changelog.md`

---

## Environment variables

```
# Anthropic
ANTHROPIC_API_KEY=sk-...

# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=habittracker
DB_USER=habituser
DB_PASSWORD=...

# App
APP_PORT=8080
APP_ENV=development
```

Never commit real values. Use a `.env.example` file with placeholder values.

---

## Local development setup (Windows / PowerShell)

```powershell
# Start PostgreSQL + pgvector via Docker
docker compose -f infra/docker-compose.yml up -d

# Run DB migrations
./gradlew flywayMigrate

# Start backend
./gradlew run

# Start frontend (once framework is chosen)
cd frontend
npm install
npm run dev
```

Run `claude doctor` if Claude Code behaves unexpectedly.

---

## What Claude should NOT do

- Do not modify files in `db/migrations/` directly — always generate a new migration file
- Do not install new dependencies without stating them explicitly in the response
- Do not commit API keys, passwords, or any secrets
- Do not skip the approval gate between agents — always pause and wait for engineer sign-off
- Do not make architectural decisions without producing an ADR
- Do not rewrite working code speculatively — make the smallest change that satisfies the PBI

---

## OpenAPI spec

OpenAPI spec lives at `backend/src/main/resources/openapi/openapi.yaml`. Any change to a request/response DTO requires a matching edit to this file in the same PR.

---

## References

- ASDLC methodology: https://asdlc.io/getting-started/
- Claude Code docs: https://code.claude.com/docs
- Anthropic API docs: https://docs.anthropic.com
- Project presentation: `docs/poc-presentation.pdf` (add after Week 1)
