# Chunk index: corruption and migration test plan

- Milestone: Prompt 08 deliverable, to be **executed in Prompt 09**
- Applies to: the encoding recommended by [ADR 0007](../decisions/0007-chunk-index-encoding.md), drafted in [`chunk-index-v1-draft.md`](../formats/chunk-index-v1-draft.md)
- Status: proposed with ADR 0007. If the owner picks another candidate, rewrite the format-specific parts (sections 2.2 and 3.2); the rest is candidate-independent.

## 1. Principles

1. **Semantics come from the reference.** Every production test that checks query results compares against `ReferenceChunkIndex` through `ChunkIndexDifferential`, rather than against hand-written expectations. Only the contract tests in `sidecar.index` define semantics directly.
2. **Corruption is never repaired silently.** A read either returns exactly what was committed or fails with a coded `ChunkIndexException`. A test that observes a third outcome (wrong data, an uncoded exception, a hang, or unbounded allocation) fails.
3. **One fault per fixture.** Each corruption fixture damages one documented thing, so a failure names its cause.
4. **The source is never changed by a failed operation.** This covers migration, compaction, export, and verification. Tests compare the bytes of every file before and after.
5. **Bounded.** Every test also runs with `-Xmx64m` against the 25 000-record dataset, and the `millions` streaming test runs with `-Xmx256m`. A length field that makes the reader allocate a huge array must fail, not exhaust the heap.

## 2. Corruption tests

### 2.1 Candidate-independent tests

| # | Fault | Expected outcome |
|---|---|---|
| X1 | A single-byte flip at every offset of a small index (exhaustive, about 50 KiB), plus 10 000 random flips on `friend-server` | Coded corruption, or a byte-identical export. Zero silent results and zero uncoded exceptions. This is the Prompt 08 `CorruptionProbe`, made exhaustive. |
| X2 | Truncation of each file at every record boundary, and at 1 000 random offsets | Coded corruption, or the last committed state when only an uncommitted tail was cut. Never data from a partial commit. |
| X3 | A whole file removed | Coded corruption that names the missing file |
| X4 | A file replaced by a valid file from an older generation of the same index | Detected: checksums bind content to the commit that references it |
| X5 | A file replaced by a valid file from another index | Detected: the index identity is in every header |
| X6 | Valid bytes in the wrong place (two region blocks swapped) | Detected: each block's header names its region |
| X7 | Length and count fields set to the maximum value | Coded corruption, with no allocation beyond the hard limits |
| X8 | Unknown major version, newer minor version, an unknown required record type, and an unknown optional record type | Fail closed for the first and third. Read-only for the second. The fourth is skipped by readers, and preserved verbatim by compaction or refused. |
| X9 | A symlink in place of a file or directory | Refused before reading (ADR 0004 path policy) |
| X10 | Read-only open of every X fixture | Zero filesystem mutations, counted through `StorageFileSystem` |

### 2.2 Format-specific tests for the recommended encoding

These cover the checkpoint blocks, the catalog, and the mutation log.

| # | Fault | Expected outcome |
|---|---|---|
| R1 | A torn **final** log frame, cut at every byte of its header, payload, and trailing checksum | The previous commit is visible. The next commit checkpoints first and never appends after the tear. |
| R2 | Damage to a **complete** log frame, at every position (header, length, payload, or checksum), including the final frame | Coded corruption on open, never a silently shortened log. The spike's first log failed exactly this; `deltaLogDamageBeforeTheEndIsCorruptionNotATornTail` is the seed test. |
| R3 | A block with a bad CRC-32C, or one whose CRC differs from the catalog's copy | Coded corruption on the first read of that block. `verify()` reports it with its region. |
| R4 | Catalog entry and block disagree: presence, count, or summary | `verify()` reports it, and queries that rely on the summary fail closed. Summaries are re-derived from blocks during verification, never trusted. |
| R5 | A logged mutation that no longer applies, for example `RecordActivity` on a chunk the checkpoint lacks | Coded corruption naming the chunk. It can only arise from damage or a writer bug, because commits validate existence. |
| R6 | A referenced segment missing; a log for a newer generation than the catalog | Coded corruption naming the file |
| R7 | An unreferenced segment, or a stale older-generation log | Ignored by readers, reported by `verify()`, and never deleted automatically (sidecar rule: no automatic deletion of unexplained files) |
| R8 | Crash at every write and `fsync` of a commit and of a checkpoint (segment, catalog temporary file, rename, directory sync, new log), through fault injection in `StorageFileSystem`, as for the journal | After reopening, the state is exactly the previous or the new commit |
| R9 | Crash during compaction | Either the old or the new layout is fully valid, and no live block is lost |

## 3. Migration tests

### 3.1 Policy being tested

- Format migrations are explicit, journaled operations (ADR 0006) that write a **new** index beside the old one.
- The old index is retained until the owner removes it.
- The migration verifies the new index against the old one through the canonical diagnostic export.
- Never in place, and never on open.

### 3.2 Tests

| # | Test | Expected outcome |
|---|---|---|
| M1 | Golden v1 index fixtures: about 2 000 records covering legacy chunks, multi-season activity, protection, negative and extreme coordinates, and two dimensions. Each fixture has a `.meta` file holding its export checksum. | The current reader produces the recorded export, byte for byte |
| M2 | Export → import → export round trip | Byte-identical exports |
| M3 | Migration v1 → v1′ (a synthetic next version, used to prove the machinery before a real v2 exists) | The new index's export equals the old index's export. The old index's bytes are unchanged. |
| M4 | Migration interrupted at every journal step | Recovery recommendation as in ADR 0006. Old index intact. The new index is either absent or complete and verified, never half-switched. |
| M5 | Migration of a corrupt source | Refused before writing anything, with the source's coded corruption |
| M6 | Downgrade attempt: an older reader on a newer-minor index | Read-only. Any write attempt is rejected with a coded error. |
| M7 | Manifest compatibility: a v1.x manifest that references the index area | Older manifest readers fail closed on the unknown reference kind (manifest major or minor policy, ADR 0005) |

## 4. What Prompt 08 already executed

The spike executed a subset against every candidate, so the comparison is fair:

- `CandidateDifferentialTest` performs differential equality after bulk load, random updates, and reopen, on four datasets.
- `CorruptionProbe` is X1 with 300 random flips, on the read path and the verify path.
- `CrashProbe` is the process-kill form of R8, with 40 trials. For C1-log, the writer also checkpoints every 5 commits, so kills land inside checkpoints too.

Results are in [`docs/benchmarks/chunk-index/results.md`](../benchmarks/chunk-index/results.md). Exhaustive flipping, truncation, fault-injected `fsync`, and all migration tests are left for Prompt 09, against the production implementation.
