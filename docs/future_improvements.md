# Future Improvements (Post-Phase 4)

The following items were identified during Phase 4 architecture and
implementation. They are out of scope for the current PoC but are
recorded here so a future PBI can pick them up with context.

## CHUNKING

Notes longer than ~500 tokens should be split before embedding.
Current implementation embeds the full note as a single vector.
Long notes produce averaged embeddings that lose specific detail.

## EMBEDDING CACHE

Re-embedding identical content wastes API calls and money.
A simple content-hash lookup in a cache table would prevent duplicate
embeddings.

## TOKEN BUDGET

No enforcement of maximum token count on retrieved context before
prompt assembly. Long retrieved content can push the prompt over the
model's context window. A token counter on the assembled prompt with
truncation logic is needed.

## EVAL PERSISTENCE

Phase 4 originally specified a `POST /tips/evaluate` endpoint with a
keyword-match metric; it was removed per engineer decision 2026-04-29
(see ADR-011 §7). A future revival should use a labelled question
set, recall@K, an LLM-as-judge metric, and persisted results to
enable longitudinal RAG-quality analysis. Persisting (userId,
question, foundKeywords, narrative, timestamp) enables offline
analysis of RAG quality over time.

## FRAMEWORK INTRODUCTION POINT

Phase 4 completes a hand-rolled RAG pipeline.
This is now an appropriate point to introduce LangChain4j as a
comparison: build the same pipeline using the framework and compare
code volume, abstraction quality, and what the framework hides vs
reveals.
