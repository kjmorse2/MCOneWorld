# Chunk index, version 1: **DRAFT** (not normative)

- Status: **Draft**, accepted in principle by [ADR 0007](../decisions/0007-chunk-index-encoding.md) on 2026-10-01. Prompt 09 turns it into the normative `chunk-index-v1.md`, which supersedes this file, and may change details where the implementation or its tests show a need. Any change to the commit path is re-measured with the Prompt 08 harness.
- Format name: `mconeworld-chunk-index`, version `1.0`
- Contract: the `sidecar.index` API (`ChunkIndex`, `ChunkMutation`, `ChunkQuery`, `ChunkOrder`). This format stores exactly what `ChunkProvenance` holds, and nothing that can't be derived from it.
- Measured as: the spike candidate `c1-region-log` (`RegionLogIndex` over `RegionStoreIndex` with segment layout). The spike's files have no format-version headers; this draft adds them (§3).

## 1. Design in one paragraph

The index is a **checkpoint** plus a **delta log**:

- **Checkpoint.** Records are grouped by Minecraft region (32 × 32 chunks) into compressed, checksummed **region blocks**. Blocks are appended to **segment files** and never modified in place. A **catalog** says where each region's current block is, and summarizes it.
- **Delta log.** A commit appends one CRC-framed delta with the batch's **mutations** to the log for the current catalog, then makes one `fdatasync`. Readers fold the logged mutations over the checkpoint.
- **Checkpointing.** A checkpoint folds the deltas into new blocks, writes a new catalog atomically, and starts an empty log. It runs explicitly, and also within a commit once the deltas exceed a bound on how many records they may hold in memory.

Region blocks follow the world's own layout, so damage to one block loses at most those 1 024 chunks' provenance, and verification names that region.

## 2. Location and ownership

```text
mconeworld-data/
└── chunk-index/                      reserved, mutable area (like operations/)
    ├── catalog                       the checkpoint (atomically replaced)
    ├── deltas-<generation>.log       commits since that checkpoint
    └── data/
        └── seg-<n>.dat               region blocks: append-only, immutable once rolled
```

- **Reserved and outside the manifest.** `chunk-index` becomes a reserved top-level name. Manifests can't reference paths under it, and it isn't written through sidecar transactions. The reason is the same as for `operations/` (ADR 0006 §2): a manifest commit per index flush would churn the revision that operations pin.
- **One writer, lock-free readers.**
  - The writer runs under the sidecar writer lock (ADR 0004).
  - Readers need no lock. A reader's snapshot is a catalog together with the logged mutations it saw, and both are immutable.
- **Segments and logs are never appended after unverified bytes.**
  - Each writer session starts a new segment, and a segment rolls at 64 MiB.
  - A delta log that ends in a torn frame is never appended to. The next commit checkpoints first, which starts a fresh log.

## 3. Common rules

- Integers are big-endian, or LEB128 varints. Signed varints are zig-zag. UUIDs are 16 bytes, most significant half first.
- **Version headers.** Every file starts with a magic number, the format major and minor (u16 each), and the **index id** (a UUID chosen at creation). Each file type has its own magic:

  | File | Magic |
  |---|---|
  | catalog | `MCOC` |
  | segment | `MCOS` |
  | delta log | `MCOL` |

  A block or delta copied from another index therefore can't pass as this one's.
- Every structure ends in a CRC-32C, and the catalog also stores each block's CRC-32C. A file's own CRC detects damage, and the catalog's copy ties each block to the checkpoint that references it.
- **Evidence byte:** `source << 2 | confidence`, with explicit codes. These are never enum ordinals.

  | Source | Code | | Confidence | Code |
  |---|---|---|---|---|
  | `RUNTIME_OBSERVATION` | 0 | | `HIGH` | 0 |
  | `OFFLINE_SCAN` | 1 | | `MEDIUM` | 1 |
  | `REGION_FILE_TIMESTAMP` | 2 | | `LOW` | 2 |
  | `INHABITED_TIME` | 3 | | | |
  | `WORLD_IMPORT` | 4 | | | |
  | `ADMINISTRATOR` | 5 | | | |

## 4. Region block (in a segment)

```text
"MCOB"  u8 block version
utf8    dimension key
i32     regionX, i32 regionZ
varint  season count S, then S × 16-byte season ids, sorted     (the block's dictionary)
zigzag  base epoch second
128     presence bitmap: bit i = local index i = localZ*32 + localX
varint  raw length, varint deflated length, raw DEFLATE (RFC 1951) of the records
u32     CRC-32C of every preceding byte of the block
```

Records follow in local-index order, one per present bit:

```text
u8      flags: bit 0 legacy, bit 1 protected (other bits must be 0)
varint  first season index            (absent when legacy)
zigzag  observedAt seconds − base;  varint nanos (0 … 999 999 999);  u8 evidence
varint  activity count A (≤ S), then A ×:
        varint season index;  zigzag seconds − base;  varint nanos;  u8 evidence
```

**Decoding a block:**

1. Verify the CRC and the header.
2. Inflate to exactly the recorded length.
3. Walk every record once without allocating, to check every bound and build a per-record offset table.

A **cache** holds blocks in this compact form (inflated bytes plus offsets), budgeted in bytes. A lookup decodes only the record it needs.

The spike first cached decoded record objects. That ran out of heap under `-Xmx256m` on 9.5 million records, which is why the compact form is now part of the design (see the results).

## 5. Catalog (the checkpoint)

```text
"MCOC"  format major, minor, index id
u64     generation g
varint  next segment number
varint  dimension count, per dimension: utf8 key, varint region count, per region (region-major):
        i32 regionX, i32 regionZ, varint segment, varint offset, varint length, u32 block CRC-32C
        128 presence bitmap;  varint count
        summary: allBox, legacyCount, legacyBox, protectedCount, protectedBox,
                 varint season count, per season: id (16), firstCount, firstBox,
                 activeCount, activeBox, touchedCount
u32     CRC-32C of every preceding byte
```

A box packs local coordinates as `minX | minZ<<8 | maxX<<16 | maxZ<<24`, or is −1 for none.

Summaries are **derived** data. They let queries skip regions and answer whole-dimension counts and envelopes without reading blocks. `verify()` recomputes them from the blocks; a mismatch is corruption, and a summary is never trusted over its block.

The catalog is replaced atomically by writing `catalog.tmp`, `fsync`ing it, renaming it over `catalog`, then `fsync`ing the directory. It is rewritten **only at checkpoints**: it is O(regions), about 3–5 MB at 16 000 regions.

## 6. Delta log (mutations, not records)

```text
"MCOL"  format major, minor, index id, u64 generation g     (must equal the catalog's)
frames: u32 payload length L | u32 CRC-32C(length bytes) | payload | u32 CRC-32C(payload)
payload: varint season count, 16-byte seasons (the frame's dictionary)
         varint chunk count; per chunk: utf8 dimension, i64 ChunkOrder key,
         varint mutation count, then per mutation (in the order they were applied):
           1 ObserveFirst   u8 legacy; [varint season]; zigzag seconds; varint nanos; u8 evidence
           2 RecordActivity varint season; zigzag seconds; varint nanos; u8 evidence
           3 SetProtection  u8 (0 NONE, 1 PROTECTED)
           4 Replace        record (§4 layout, base second 0)
           5 Remove         (nothing)
```

**The log holds `ChunkMutation`s, not resolved records.**

- **Validation needs no reads.** A batch validates each mutation by **existence** alone, taken from the catalog's presence bitmaps plus earlier logged mutations. `ChunkMutation.applyTo` rejects only `RecordActivity` and `SetProtection` on a chunk without a record, so existence is all it needs.
- **Resolution uses the contract.** Readers and checkpoints resolve a chunk by folding its logged mutations over its checkpoint record through `applyTo`, the contract's single definition of semantics.
- **Measured.** Logging resolved records instead forced a record read per mutation, and commits took about 230 ms on 9.5 million records. Logging mutations brought them to 16 ms ([results](../benchmarks/chunk-index/results.md) §4, item 4).

**Commit.** Append one frame and `fdatasync` the log. `commit()` returns only after that. A commit's cost is proportional to its own size, and does not depend on the index size or on any reads.

**Torn tail or corruption.**

- **Torn:** fewer bytes than a frame header remain, or a frame header is valid but its payload or checksum runs past the end of the file. It is an unacknowledged commit, and is ignored.
- **Corruption:** a header-checksum mismatch, or a complete frame with a payload-checksum mismatch, anywhere in the file, the last frame included. It fails closed with a coded error. Without the header checksum, a damaged length could pose as a torn write.
- **Why this matters:** the spike's first log treated any invalid frame as the tail, and silently dropped acknowledged commits. This is the same rule as ADR 0006 §7 for journals.

**Open, which never writes.**

1. Read the catalog for generation `g`.
2. Replay `deltas-g.log` up to the torn tail, if there is one.
3. A log for a generation **older** than `g` is stale: a checkpoint completed after it. It is ignored, and may be deleted only by explicit maintenance.
4. A log for a **newer** generation is corruption.

A log with a torn tail is never appended to: the next commit checkpoints first, which starts a fresh log.

## 7. Checkpoint, compaction, snapshots

**Checkpoint.** Runs explicitly, or within a commit once 100 000 mutations are logged (a memory bound).

1. Resolve every logged chunk, encode new blocks for the regions they touch, and append them to the current segment.
2. Sync the segment.
3. Write and swap the catalog for generation `g+1`.
4. Start `deltas-(g+1).log` on the next commit.

A crash at any step leaves either catalog `g` with its log intact, or catalog `g+1`, whose older log is then stale.

**Compaction** is explicit maintenance, never implicit.

1. Copy the live blocks of mostly-dead segments forward, byte for byte. Blocks are position-independent.
2. Checkpoint.
3. Once no reader holds an older catalog, delete the old segments. This is the only destructive step, and it removes only segments that the newest catalog and every open reader provably don't reference.

**Snapshot for backup.**

1. The writer reports the catalog generation, the segments that catalog references, and the length of the log at the moment of the snapshot.
2. Copy exactly those bytes.

This gives a consistent backup without stopping the writer. Copying the directory naively while a writer runs is **not** guaranteed to be consistent.

## 8. Versioning

- Every header carries the format major and minor.
  - An unknown **major** fails closed with `unsupported_version`.
  - A newer **minor** is read-only: the reader may query but must not commit, checkpoint, or compact. The policy is the same as for journals (ADR 0006 §8).
- A minor version may add trailing optional fields and optional sections. It may not add new codes to existing enum fields: a new evidence source or confidence is a major change, because old readers can't represent it.
- Compaction copies blocks it doesn't need to understand verbatim, and refuses to rewrite blocks it can't decode.

## 9. Diagnostic export: `mconeworld-chunk-index-export` 1.0

The readable form for people, diffing, and migration verification. It is **not** canonical storage.

- The first line is a header object: `{"format":"mconeworld-chunk-index-export","version":"1.0","indexId":…,"generation":…}`.
- One line per record follows, in dimension order and then `ChunkOrder`, each line compact canonical JSON (ADR 0003, `CanonicalJsonWriter.writeCompact`):

  ```json
  {"activity":[{"confidence":"HIGH","lastObservedAt":"2026-05-01T10:00:00.123456Z","season":"…","source":"RUNTIME_OBSERVATION"}],"dimension":"minecraft:overworld","first":{"confidence":"HIGH","legacy":false,"observedAt":"…","season":"…","source":"RUNTIME_OBSERVATION"},"protection":"NONE","x":-1,"z":7}
  ```

- Deterministic, so the SHA-256 of an export is a semantic checksum. Migration (test plan M1–M4) and golden fixtures compare exports.

## 10. Limits

| Limit | Value |
|---|---|
| Records per block | 1 024 |
| Season dictionary per block or delta | ≤ 65 535 |
| Activity entries per record | ≤ the dictionary size |
| Delta frame | ≤ 64 MiB |
| Logged mutations | ≤ 100 000 before a forced checkpoint |
| Segment | rolls at 64 MiB |
| Block cache | byte-budgeted (48 MiB in the spike) |

Every count is validated against these limits **before** allocating.

## 11. Relationship to the manifest

The manifest gains, in a later minor schema version, a reference to the index area: its format, version, and index id. It does not get a checksum, because the area is mutable. This reopens the Prompt 04 resolution that indexes are checksummed `files` entries (see ADR 0007 §8).

Operations that depend on index contents record the catalog generation and the log length in their precondition snapshot (ADR 0006), so a plan detects that the index changed.

Backups record the snapshot (§7) they copied.
