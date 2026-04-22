---
name: Recurring patterns to flag in reviews
description: Code patterns that have appeared across multiple files and should be proactively checked in future reviews
type: feedback
---

## TOCTOU pre-check before repository insert
The service layer sometimes adds a `findBy*` existence check before calling `create`, even when the repository already handles the unique-violation with a typed `Left(ConflictError)`. This is redundant and introduces a time-of-check/time-of-use race window. First seen in `HabitCompletionService.recordCompletion` (PBI-008/009 review).

**Why:** The database unique constraint is the authoritative guard. The pre-check adds a round-trip and a race condition without adding safety.

**How to apply:** In any service method that calls `repo.create`, check whether the repository already handles uniqueness violations. If yes, flag any `findBy*` pre-check as a warning.

## UUID.randomUUID() called outside IO
`UUID.randomUUID()` used as a `val` binding (`=` not `<-`) inside a `for`-comprehension. Technically works but violates the project convention that all side effects are wrapped in `IO`. First seen in `HabitCompletionService.scala:58`.

**Why:** Project convention requires all effects in `IO`. Keeping it consistent makes the code predictable for the agent swarm and avoids surprises if the computation is moved or retried.

**How to apply:** Flag any `val x = UUID.randomUUID()` inside an `IO` for-comprehension as a warning. Correct form: `x <- IO(UUID.randomUUID())`.

## Fake clock using @volatile var with non-atomic increment
`makeFakeClock` helpers in service specs use `@volatile private var current` with `current += 1000`. This is a non-atomic read-modify-write, not thread-safe. The same pattern exists in both `HabitServiceSpec` and `HabitCompletionServiceSpec`.

**Why:** `volatile` provides visibility but not atomicity; `+=` is two operations. Safe alternative is `AtomicLong.addAndGet`.

**How to apply:** Flag in any new test clock implementation. Consider suggesting extraction to a shared `TestClocks` utility object to avoid further duplication.
