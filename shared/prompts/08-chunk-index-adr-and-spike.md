# Prompt 08 — Chunk-Index ADR, Reference Model, and Benchmarks

Do not implement the production chunk index until this milestone is reviewed and approved.

## Objective

Choose a compact sidecar representation for millions of dimension-qualified chunk provenance records using evidence rather than intuition.

## Candidate evaluation

Evaluate at least:

- SQLite with an explicit schema and transaction policy
- Region-partitioned compressed binary sets/records
- Roaring bitmap-based membership plus side tables for provenance metadata
- Another credible candidate discovered during research

Compare:

- Exact semantics supported, especially multi-season activity and nonexclusive provenance
- Incremental update behavior
- Streaming/paged reads
- Crash consistency
- Corruption detection and repair boundaries
- File count and backup behavior
- Memory use
- Query latency for membership, rectangular scans, per-season scans, and envelope calculation
- Cross-platform/library maintenance risk
- Format longevity and migration complexity
- Ability to export a readable diagnostic form

## Reference implementation

Build a deliberately simple in-memory/reference `ChunkIndex` implementation used only for semantics and differential testing. Define the API before optimizing it.

The API must not require callers to materialize all chunks and must support explicit resource ownership for iterators/cursors.

## Benchmark datasets

Generate deterministic synthetic datasets representing:

- Small friend server
- Long-lived world with sparse exploration
- Dense generated square
- Multiple distant seasons
- Negative coordinates
- Chunks revisited across seasons
- Millions of records within a documented resource budget

Do not commit enormous generated outputs.

## Deliverables

- Accepted-candidate recommendation in `docs/decisions/`
- Benchmark harness and reproducible dataset generators
- Raw benchmark result format plus summarized results
- Reference index with semantic tests
- Proposed binary/database schema and versioning strategy
- Corruption and migration test plan

## Stop conditions

Do not mark the ADR accepted or begin production implementation until the user reviews:

- Dependency/licensing consequences
- Benchmark evidence
- Backup and corruption behavior
- Migration implications

End with a decision table and your recommendation. This prompt is a design gate, not permission to implement Prompt 09.

