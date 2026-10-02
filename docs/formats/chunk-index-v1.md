# Chunk index, version 1.0 (normative)

- Format name: `mconeworld-chunk-index`, version **1.0**. Export format: `mconeworld-chunk-index-export`, version **1.0**.
- Decision: [ADR 0007](../decisions/0007-chunk-index-encoding.md) (Accepted).
- Implementation: `shared/sidecar`, package `sidecar.index`. Golden files: `shared:test-fixtures`, `fixtures/chunk-index/v1/`.
- Supersedes the Prompt 08 draft. The draft also described a single-fsync commit record in segments and two-level catalog pages; neither was adopted. What follows is what was implemented and measured.
- Contract: the `sidecar.index` API (`ChunkIndex`, `ChunkMutation`, `ChunkQuery`, `ChunkOrder`). The format stores exactly what `ChunkProvenance` holds.

## 1. Overview

An index is a **checkpoint** plus a **delta log**.

- **The checkpoint.** Records are grouped by region (32 × 32 chunks) into region blocks. Blocks are compressed in units of rows and checksummed, then appended to segment files and never modified in place. A **catalog** says where each region's current block is, and summarizes it.
- **The delta log.** A commit appends the batch's `ChunkMutation`s to the log of the current catalog generation as one checksummed frame, then makes one `fdatasync`.
- **Reads** fold a chunk's logged mutations over its checkpoint record through `ChunkMutation.applyTo`.
- **A checkpoint** folds the log into new blocks and atomically replaces the catalog.

## 2. Files

```text
mconeworld-data/
└── chunk-index/                          reserved top-level name (sidecar-storage-v1 §1)
    ├── catalog                           the checkpoint; replaced atomically
    ├── catalog.<generation>.tmp          transient: a checkpoint in progress
    ├── deltas-<generation>.log           commits since the catalog of that generation
    └── segments/
        └── seg-<number>.dat              region blocks; append-only, immutable once rolled
```

- **Name format.** `<generation>` and `<number>` are 16-digit, zero-padded decimal numbers, so names sort in order. Every name is a valid `SidecarPath`.
- **Path policy.** The sidecar's path policy applies (ADR 0004): no symbolic link anywhere below the sidecar root, including `chunk-index` itself.
- **What a file in the area can be:**

  | File | Meaning |
  |---|---|
  | `catalog` | The checkpoint. |
  | `deltas-<g>.log` where `g` is the catalog's generation | The current log. |
  | `deltas-<g>.log` where `g` is older | **Stale**: its checkpoint completed. |
  | `catalog.<g+1>.tmp` | An **interrupted checkpoint**: never committed. |
  | A segment the catalog doesn't reference | An **orphan**. |
  | Anything else | **Unexplained**: reported, and it blocks writing. |

  Stale logs, interrupted checkpoints, and orphans are harmless. They are removed only by explicit maintenance (`removeObsoleteFiles`). Unexplained files are never removed by software.

## 3. Common encodings

- **Integers.** Big-endian, or LEB128 varints. Signed varints are zig-zag. UUIDs are 16 bytes, most significant half first.
- **Strings.** A varint byte length, then strict UTF-8.
- **Times.** Seconds relative to a base second (zig-zag), plus nanoseconds (varint, 0 … 999 999 999). This is lossless for every representable `Instant`.
- **Common file header** (36 bytes), used by the catalog, the log, and segments:

  ```text
  4  magic: "MCOC" catalog, "MCOL" log, "MCOS" segment
  2  format major (1)        2  format minor (0)
  16 index id (UUID, chosen at creation)
  8  number: generation (catalog, log) or segment number
  4  CRC-32C of the preceding 32 bytes
  ```

- **Season dictionary.** A varint count (at most 65 535), then 16-byte season ids, strictly increasing (sorted and unique).
- **Evidence byte.** `source << 2 | confidence`, with fixed codes:

  | Source | Code | | Confidence | Code |
  |---|---|---|---|---|
  | `RUNTIME_OBSERVATION` | 0 | | `HIGH` | 0 |
  | `OFFLINE_SCAN` | 1 | | `MEDIUM` | 1 |
  | `REGION_FILE_TIMESTAMP` | 2 | | `LOW` | 2 |
  | `INHABITED_TIME` | 3 | | | |
  | `WORLD_IMPORT` | 4 | | | |
  | `ADMINISTRATOR` | 5 | | | |

- **Record** (inside units and log frames):

  ```text
  u8     flags: bit 0 legacy, bit 1 protected; other bits 0
  varint first season index                 (absent when legacy)
  time   first observedAt;  u8 evidence
  varint activity count A (≤ dictionary size), then A × (varint season index, time, u8 evidence)
  ```

## 4. Segments and region blocks

A segment is the common header (`MCOS`, its number) followed by blocks, back to back.

A block:

```text
"MCOB"  u32 header length H (≤ 1 MiB)  header (H bytes)  u32 CRC-32C of the preceding 8 + H bytes
unit 0 stored bytes, unit 1 stored bytes, …

header: utf8 dimension;  i32 regionX;  i32 regionZ
        season dictionary
        zig-zag base second
        128 bytes presence bitmap (16 big-endian u64 words; bit i of the region = local index i,
                                   local index = localZ × 32 + localX)
        u8 rows per unit R ∈ {1, 2, 4, 8, 16, 32}
        per unit (32 / R units): varint raw length, varint stored length, u32 CRC-32C(stored bytes)
```

- **Units.** Unit `u` holds the present records of local indexes `[u·R·32, (u+1)·R·32)`, in order, compressed as raw DEFLATE (RFC 1951). A unit with no records has raw and stored length 0, and its CRC is 0.
- **Lookup cost.** A lookup reads the header once (it is then cached) and one unit.
- **Default layout.** Writers use R = 4. That was measured in Prompt 09: uniform lookups on 9.5 million records take 15 µs p50, against 57 µs with R = 32, at 6 % more disk. Readers accept any valid R, so the default can change without a format change.
- **Determinism.** Encoding is deterministic. The dictionary holds every season of the block, sorted; the base second is the smallest second in the block (0 if none); and records follow local-index order.

## 5. Catalog

The catalog is the common header (`MCOC`, its generation), then:

```text
varint next segment number (every referenced segment is lower)
varint dimension count (≤ 4 096); per dimension, in DimensionKey order:
  utf8 dimension;  varint region count;  per region, in ChunkOrder (region-major):
    i32 regionX  i32 regionZ  varint segment (≥ 1)  varint offset (≥ 36)
    varint block length  varint block header length H  u32 block header CRC-32C
    128 bytes presence bitmap   varint record count (1 … 1 024; equals the bitmap's bit count)
    summary
u32 CRC-32C of every preceding byte of the file
```

Summary:

```text
i32 all box;  varint legacy count;  i32 legacy box;  varint protected count;  i32 protected box
varint season count; per season (sorted): 16-byte id, varint first count, i32 first box,
  varint active count, i32 active box, varint touched count
```

A box packs region-local coordinates as `minX | minZ << 8 | maxX << 16 | maxZ << 24`, each 0 … 31, or is −1 when empty.

**Binding.** The block's header length, header CRC, presence bitmap, region, and total length must equal the catalog's entry. A stale or foreign block therefore can't pass for the right one.

**Summaries** are derived data. Readers use them to skip regions and to answer whole-dimension counts and envelopes when the log doesn't touch the dimension. Verification recomputes them from the blocks, and a mismatch is `summary_mismatch`.

## 6. Delta log

The log is the common header (`MCOL`; number = the catalog's generation), then frames:

```text
u32 payload length L (≤ 64 MiB) | u32 CRC-32C of the 4 length bytes | payload | u32 CRC-32C(payload)

payload: season dictionary
         varint chunk count; per chunk (each at most once per frame):
           utf8 dimension;  i64 ChunkOrder key;  varint mutation count; per mutation, in order:
             1 ObserveFirst   u8 legacy (0/1); [varint season]; time (base 0); u8 evidence
             2 RecordActivity varint season; time (base 0); u8 evidence
             3 SetProtection  u8 (0 NONE, 1 PROTECTED)
             4 Replace        record (base 0)
             5 Remove
```

**Why mutations, not records.** Each mutation is validated at commit by whether its chunk **exists**, using the catalog's presence bitmaps and the earlier logged mutations. `applyTo` rejects only `RecordActivity` and `SetProtection` without a record, so existence is all it needs. A commit therefore reads no block, and costs one `fdatasync` whatever the index size.

**Torn tail versus corruption.**

| Damage | Meaning | Outcome |
|---|---|---|
| A log shorter than its header | Its creation was interrupted, so no commit is in it | Torn tail |
| Fewer than 8 bytes left for a frame header | An interrupted write | Torn tail |
| A valid frame header whose payload or CRC runs past the end of the file | An interrupted write | Torn tail |
| A frame-header CRC mismatch | Damage | Corruption (`log_corrupt`), wherever it is |
| A complete frame with a payload CRC mismatch | Damage | Corruption (`log_corrupt`), even as the last frame |

A torn tail is an unacknowledged commit, and readers ignore it. A log with a torn tail is never appended to: the next commit checkpoints first, which starts the next generation's log.

## 7. Protocols

**Create (explicit only).**

1. Create `chunk-index/` and `segments/`, syncing each parent directory.
2. Write catalog generation 0, with no regions and next segment 1, through `catalog.0000000000000000.tmp` and an atomic rename.

The area must not exist, or must be empty.

**Commit.**

1. Validate the batch by existence.
2. If the log is torn, checkpoint first.
3. Create the log if needed: header, then `fsync`, then sync the directory.
4. Append one frame and `fdatasync` it.
5. Publish the new state.
6. If the logged mutations reach the checkpoint threshold (100 000 by default), checkpoint.

**Checkpoint.**

1. Resolve every logged chunk against its checkpoint record.
2. Re-encode each touched region and append the blocks to the session's segment. A segment is new for each writer session, rolls at 64 MiB, and skips any existing number, because an orphan is never reused or deleted here.
3. `fsync` the segment, and sync the directory if the segment is new.
4. Write `catalog.<g+1>.tmp` and `fsync` it. A leftover temporary file with exactly this name is provably from an interrupted checkpoint of this generation, and is deleted first.
5. Rename it over `catalog` and sync the directory. **This rename is the commit point.**
6. Delete `deltas-<g>.log`.

**Compaction** is explicit. It checkpoints, and also copies, byte for byte, every live block of each segment that is less than half live into the current segment.

**Maintenance** is explicit. It deletes segments that neither the current catalog nor an open in-process reader references, stale logs, and the interrupted checkpoint's temporary catalog.

**Failure.** If a commit or checkpoint fails after it may have written, the handle refuses further writes (`handle_broken`). Reopening reads exactly what is on disk: the previous or the new state. The fault-injection test proves this at every step.

**Open (read-only, never writes).**

1. Read and verify the catalog.
2. Replay `deltas-<g>.log`, if present.
3. Re-read the catalog header. If the generation moved, or the log vanished because another process checkpointed, retry, up to 5 times.

## 8. Versions

- **Unknown major:** `unsupported_version`, for any file. This fails closed.
- **Newer minor:** the index is readable but **read-only**. Opening a writer fails with `read_only`, and inspection warns `newer_minor_version`.
- **What a minor version may add:** trailing optional data only.
- **What needs a major version:** new enum codes (an evidence source or confidence), new mutation types, and any change to the meaning of an existing field.
- **Migration** between majors is an explicit, journaled operation (ADR 0006). It builds a new index beside the old one and compares the canonical exports before switching. It never runs on open. The test plan's §3 lists the tests.

## 9. Limits

Every count is checked against these limits, and against the bytes remaining, **before** anything is allocated.

| Limit | Value |
|---|---|
| Records per block | 1 024 |
| Seasons per dictionary | 65 535 |
| Dimensions | 4 096 |
| Block header | 1 MiB |
| Unit (raw) | 16 MiB |
| Block | 64 MiB |
| Frame payload | 64 MiB |
| Catalog file | `maxCatalogBytes`: 512 MiB by default, 2 GiB at most |
| Log file | `maxLogBytes`: 1 GiB by default, 2 GiB at most |
| Block cache | `cacheBytes`: 48 MiB by default |

## 10. Diagnostic export: `mconeworld-chunk-index-export` 1.0

One compact canonical JSON object per line (ADR 0003), each followed by an LF:

```json
{"format":"mconeworld-chunk-index-export","generation":1,"indexId":"…","logBytes":328,"version":"1.0"}
{"activity":[{"confidence":"HIGH","lastObservedAt":"2026-05-28T20:27:40Z","season":"…","source":"RUNTIME_OBSERVATION"}],"dimension":"minecraft:overworld","first":{"confidence":"MEDIUM","legacy":true,"observedAt":"2026-05-28T20:26:41Z","season":null,"source":"WORLD_IMPORT"},"protection":"NONE","x":-1,"z":-1}
```

- **Order and form.** Records follow dimension order and then `ChunkOrder`. Times are ISO-8601 UTC in their shortest exact form. A legacy first observation has `"season": null`.
- **Determinism.** The record lines are a function of the records alone, so their SHA-256 is a semantic checksum of an index.
- **Reading.** `ChunkIndexExport.read` parses strictly: exact member sets, types, and values, with the line number in every error.
- **Not canonical storage.** Nothing reads an export as the index.

## 11. Concurrency

- **One writer per sidecar**, enforced by the writer lock, and **one index handle per writer**, through the `IndexFileWriter` claim.
- **Readers.**
  - A reader is a snapshot (a catalog plus the logged mutations it saw) and is used from one thread at a time.
  - Checkpoints never invalidate readers: blocks are immutable, and maintenance never deletes a segment an open reader needs.
  - Readers in other processes see a consistent committed state through the open-retry protocol of §7. If maintenance deletes a segment their older snapshot needs, they fail with `segment_missing` and should reopen.
- **Closing the handle** invalidates its readers, cursors, and batches.

## 12. Relationship to operations and the manifest

**Operations.** An operation that depends on the index records `ChunkIndexState.pin()` under the key `sidecar:chunk-index` in its precondition snapshot. The pin covers the index id, the generation, the log length, the catalog CRC, and the CRC of the log's last frame, so any commit or checkpoint changes it.

**The manifest.** Owner decision D2 says the manifest will reference the area by format, version, and index id, without a checksum. That reference arrives with the first manifest minor version that needs it, under ADR 0005, and is not part of manifest 1.0.

## 13. Changes to this document

| Version | Change |
|---|---|
| 1.0 | Initial format (Prompt 09). Pinned by the golden files of `ChunkIndexFixtures`. |
