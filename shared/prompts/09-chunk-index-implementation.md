# Prompt 09 — Production Chunk Index

Implement the chunk-index design accepted after Prompt 08. Do not substitute a different technology without reopening the ADR.

## Objective

Provide a versioned, bounded-memory, corruption-detecting production `ChunkIndex` implementation that is semantically equivalent to the reference model.

## Requirements

1. Implement every required query and update through the established interface.
2. Preserve dimension identity, legacy provenance, first observation, later activity, evidence/confidence, and protected state.
3. Support streaming or paging for large scans.
4. Integrate with sidecar checksums, safe paths, locking, staging, and journaling.
5. Version the index encoding independently from the manifest.
6. Provide readable diagnostic export without making that export the canonical format.
7. Detect corrupt headers/pages/partitions/checksums and report precise scope.
8. Bound allocations and validate counts before allocation.
9. Close resources deterministically.
10. Document concurrency: expected single writer, supported readers, snapshots, and invalidation behavior.

## Tests

- Differential randomized testing against the reference implementation.
- Set/query algebra across positive and negative coordinates.
- Multi-season and legacy provenance cases.
- Deterministic output where the chosen format promises it.
- Corrupt/truncated/oversized input at each structural layer.
- Interrupted transaction/write at injected failure points.
- Reopen after clean and failed writes.
- Lock contention.
- Format-version rejection.
- Diagnostic export round-trip comparison at the semantic level.
- Memory/time regression tests using approved thresholds and non-flaky methodology.

Run the Prompt 08 benchmarks and compare results with its recorded baseline. Explain meaningful regressions rather than adjusting thresholds silently.

## Constraints

- Do not expose implementation-specific database/compression classes through the public interface.
- Do not silently drop a corrupt partition and continue as if the index were complete.
- Do not provide automatic destructive repair.

## Acceptance criteria

The production index matches the reference semantics across randomized tests, meets the approved resource budgets, survives fault injection without corrupting the last committed state, and has a documented migration/version story.

