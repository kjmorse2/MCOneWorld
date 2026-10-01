# ADR 0007: Chunk-index encoding

- Status: **Proposed**, 2026-09-30. Prompt 08 is a design gate. This ADR may be accepted **only by the project owner**, after reviewing:
  - the dependency and licensing consequences (§6.1)
  - the benchmark evidence (§4)
  - the backup and corruption behaviour (§7)
  - the migration implications (§8)

  No production chunk-index code may be written before it is accepted (Prompt 09).
- Milestone: Prompt 08
- Evidence:
  - [`docs/benchmarks/chunk-index/`](../benchmarks/chunk-index/README.md): budgets committed before measuring, raw results, and the summary
  - the spike code in `shared/sidecar/src/bench/`
- Proposed format: [`docs/formats/chunk-index-v1-draft.md`](../formats/chunk-index-v1-draft.md)
- Test plan: [`docs/architecture/chunk-index-test-plan.md`](../architecture/chunk-index-test-plan.md)

## 1. Context

The sidecar must keep chunk **provenance** for every chunk MCOneWorld knows about. Provenance is evidence, never ownership (root `AGENTS.md` §9). A record has:

- the first observation (a known season, or legacy)
- at most one activity entry per season
- evidence and confidence for every claim
- explicit protection

Long-lived worlds reach millions of records. Root §9 forbids settling the encoding inside a feature change: it needs its own decision, benchmarks, corruption tests, and migration strategy.

Two products use the index:

- **Paper** records observations in small, durable batches while a game server shares its heap and disk.
- **The offline tool** imports whole worlds, previews plans over large areas, and exports diagnostics.

What Prompt 08 built first, independent of any encoding:

- **The contract, in `sidecar.index` (API only).** Streaming cursors and snapshot readers with explicit ownership, plus atomic batches that a rejected mutation poisons. Mutation semantics (`ChunkMutation.applyTo`), query semantics (`ChunkQuery.matches`), and scan order (`ChunkOrder`) are defined once in shared code, so storage can't change meaning.
- **A reference implementation**, `ReferenceChunkIndex`, deliberately simple and in memory, with semantic and property tests.
- **Seven deterministic synthetic datasets**, from 26 668 records up to 9 548 253. They are streamed one region at a time and never committed.
- **Budgets**, committed in `ab5a0fa0` before any candidate existed.

## 2. Candidates

All four required families were built as working implementations of the same contract. Each shares the lifecycle code (`SpikeIndexBase`) and, where applicable, the record bytes (`RecordCodec`), so measured differences come from the storage engine.

| Id | Candidate | Dependency |
|---|---|---|
| C1-files | Region-partitioned compressed record blocks, **one immutable file per block version**, with a catalog file swapped atomically per commit | none (JDK only) |
| C1-segments | The same blocks **appended to 64 MiB segment files**, with compaction | none |
| C2 | **SQLite** in WAL mode (`synchronous=FULL`), with an explicit schema and secondary indexes | `org.xerial:sqlite-jdbc` 3.53.4.0 (native) |
| C3 | **Roaring bitmaps** for membership and the per-season, legacy, and protected sets, with C1-segment blocks as the metadata side tables. All bitmaps are committed atomically with the catalog. | `org.roaringbitmap:RoaringBitmap` 1.6.23 |
| C4 | **H2 MVStore**, a pure-Java, log-structured, copy-on-write B-tree, with secondary key sets. This is the fourth "credible candidate". | `com.h2database:h2-mvstore` 2.5.252 |
| **C1-log** | C1-segment blocks as a **checkpoint**, plus an append-only, CRC-framed **log of mutations** as the commit path. Added *after* measurement showed C1-segments' commit cost (§4.3). | none |

Considered but not built:

- **LMDB** (`lmdbjava`): native, with a fixed map size, and its file growth on Windows needs care. It shares C2's native-library risks and has weaker tooling.
- **LevelDB** ports: the pure-Java ports are unmaintained, and MCA Selector's `pcmind` fork is a single-maintainer fork.
- **Parquet or Arrow**: immutable columnar files with a heavy dependency tree, a poor fit for small durable updates.
- **Plain JSON Lines**: no random access, and roughly 10× the size. It remains the right *export* format (§5).

**Correctness gate before measuring.** `CandidateDifferentialTest` compares every candidate with the reference after a bulk load, after random update batches, and after reopening from disk. It runs on four datasets, including the extreme `int` corner, and is part of `check`. It caught a real bug in C1: `Long.MIN_VALUE` was used as a "no start" sentinel, but it is the key of chunk (−2³¹, −2³¹). The fixed sentinel rule is now documented on `ChunkOrder`.

## 3. Method

See [`docs/benchmarks/chunk-index/README.md`](../benchmarks/chunk-index/README.md).

- Every case runs in fresh JVMs: a build JVM, then a query JVM. The `millions` dataset runs with `-Xmx256m` (B1).
- Inputs are generated before timing.
- The corruption probe flips one random byte, 300 times per candidate.
- The crash probe sends 40 `SIGKILL`s to a writer mid-commit, and checks that the index reopens to exactly the acknowledged or the pending state.

## 4. Evidence

The full tables are in [`results.md`](../benchmarks/chunk-index/results.md), and the raw lines are in `docs/benchmarks/chunk-index/raw/`.

**Conditions.** One machine: a Ryzen 7 7800X3D and an NVMe SSD with ext4 on LVM. A median 4 KiB write-plus-`fsync` takes **16 ms** here. That is slow for NVMe, so every durable-commit number below is pessimistic, and the floor for any candidate's commit is one `fsync`.

### 4.1 Gate budgets

| Gate | C1-files | C1-segments | **C1-log** | C2 SQLite | C3 Roaring | C4 MVStore |
|---|---|---|---|---|---|---|
| B1: `millions` (9.5 M records) under `-Xmx256m` | not run (dominated, §4.3 item 1) | ✅ after the cache fix (§4.3 item 3) | ✅ | ✅ | ✅ after the cache fix | ✅ |
| B2: silent wrong reads after a single-byte flip (300 trials) | ✅ 0 | ✅ 0 | ✅ 0 after the torn-tail fix (§4.3 item 5); 300 before | ❌ **86** (46 even after `PRAGMA integrity_check`) | ✅ 0 | ❌ **61** (60 after full verification) |
| B3: reopen after `SIGKILL` mid-commit (40 trials) | ✅ 40/40 | ✅ 40/40 | ✅ 40/40 | ✅ 40/40 | ✅ 40/40 | ✅ 40/40 |

**B2 is decisive.** SQLite has no data-page checksums, and MVStore checksums chunk headers but not page contents. For both, one flipped byte in 20–29 % of positions returned *different records with no error*. This held even after each engine's own integrity check, and 12 SQLite trials also crashed with uncoded exceptions.

Root `AGENTS.md` requires that corruption never hides behind defaults, and that "binary/indexed records must have checksums". Silent wrong provenance could make a destructive operation trust the wrong chunks.

The region-block candidates detected every flip that mattered through CRC-32C:

| Candidate | Detected | Harmless |
|---|---|---|
| C1-files | 300 / 300 | 0 |
| C1-segments | 288 | 12 |
| C3 | 284 | 16 |
| C1-log | 300 / 300 | 0 |

"Harmless" means the flip landed in bytes no reader uses, such as superseded blocks, and the export stayed byte-identical.

C1-log's probe dataset stays under the checkpoint threshold, so that probe exercised its log. Its blocks and catalog are C1-segments' code, which C1-segments' probe exercised.

### 4.2 Target budgets, worst case across datasets

| Budget | `c1-region-files` | `c1-region-segments` | `c1-region-log` | `c2-sqlite` | `c3-roaring` | `c4-mvstore` |
|---|---|---|---|---|---|---|
| B4 lookup p50 ≤ 20 µs, p99 ≤ 1 ms (uniform) | ✅ 481 ns / 147.0 µs (revisited) | ❌ 62.8 µs / 721.0 µs (millions) | ❌ 63.3 µs / 721.2 µs (millions) | ✅ 9.9 µs / 19.2 µs (millions) | ❌ 63.1 µs / 715.1 µs (millions) | ✅ 7.4 µs / 94.6 µs (millions) |
| B4 lookup p50 ≤ 20 µs, p99 ≤ 1 ms (local) | ✅ 260 ns / 701 ns (revisited) | ✅ 321 ns / 1.6 µs (millions) | ✅ 381 ns / 1.5 µs (millions) | ✅ 5.1 µs / 14.0 µs (millions) | ✅ 411 ns / 1.8 µs (millions) | ✅ 681 ns / 14.6 µs (millions) |
| B5 16×16 scan p50 ≤ 2 ms | ✅ 347.7 µs / 1.5 ms (revisited) | ✅ 888.2 µs / 6.3 ms (millions) | ✅ 895.2 µs / 7.0 ms (millions) | ✅ 971.1 µs / 3.3 ms (millions) | ✅ 875.6 µs / 7.8 ms (millions) | ✅ 926.1 µs / 28.3 ms (millions) |
| B6 scans ≥ 500 000 records/s | ✅ 2.2 M/s (season on negative-coords) | ✅ 1.1 M/s (season on millions) | ✅ 690 k/s (season on dense-square) | ❌ 184 k/s (season on millions) | ✅ 914 k/s (season on dense-square) | ✅ 532 k/s (rect512 on millions) |
| B7 season envelope on `millions` ≤ 2 s | — | ✅ 6 ms max | ✅ 7 ms max | ✅ 540 ms max | ✅ 17 ms max | ✅ 211 ms max |
| B8 1 000-mutation commit p50 ≤ 100 ms, p99 ≤ 500 ms | ❌ 6.57 s / 6.96 s (revisited) | ❌ 5.04 s / 5.39 s (millions) | ✅ 16.7 ms / 367.8 ms (revisited) | ❌ 204.9 ms / 225.8 ms (millions) | ❌ 5.16 s / 5.48 s (millions) | ❌ 194.0 ms / 219.6 ms (millions) |
| B9 bulk build ≥ 100 000 records/s (datasets ≥ 100 000 records) | ❌ 23 k/s (sparse-exploration) | ✅ 257 k/s (revisited) | ✅ 175 k/s (revisited) | ✅ 108 k/s (revisited) | ✅ 161 k/s (revisited) | ❌ 96 k/s (dense-square) |
| B10 cold open on `millions` ≤ 1 s | — | ✅ 20 ms | ✅ 39 ms | ✅ 119 ms | ✅ 143 ms | ✅ 103 ms |
| B11 ≤ 24 bytes/record (sparse, dense) | ✅ 14.2 / NaN | ✅ 14.3 / 3.1 | ✅ 14.8 / 4.8 | ❌ 77.9 / 47.9 | ✅ 15.6 / 3.2 | ❌ 36.0 / 22.2 |
| B12 files ≤ regions + 16 (`millions`) | — | ✅ 4 files | ✅ 4 files | ✅ 1 files | ✅ 5 files | ✅ 1 files |

"Worst" means the worst dataset; the generated tables in `results.md` give every value.

### 4.3 What the measurements showed (in order of discovery)

1. **One file per region is fsync-bound.** C1-files `fsync`s every touched region file, so a 1 000-mutation commit took 2.7 s even on the 25 000-record dataset. It was not run on the large datasets; that would have taken more than an hour for a result already decided.
2. **Copy-on-write region blocks amplify scattered updates.** C1-segments rewrites every touched region's block plus the catalog on each commit. That cost 0.2–0.9 s per commit at 0.1–1 M records and 5 s at 9.5 M, and wrote 0.6–2.6 KB of disk per mutation.
3. **Caching decoded records exhausts the heap.** C1-segments and C3 first held up to 262 144 decoded records in their block cache, and ran out of memory on `millions` under `-Xmx256m`, failing B1.
   - The cache now holds the compact form: inflated bytes plus a per-record offset table, budgeted in bytes, decoding one record per lookup.
   - Both then passed B1, and dense uniform lookups fell from 46 µs to 0.5 µs (p50).
4. **Commits must not read.** C1-log first logged resolved records, which meant reading each mutated chunk's record before committing. That cost about 230 ms per commit on `millions`. A GC diagnostic at 256 MiB and 1 GiB heaps showed identical latencies, ruling out memory pressure.
   - Every mutation's validity depends only on whether a record **exists**, which the catalog's presence bitmaps answer without a read. So the log now stores the mutations themselves, and reads fold them through `ChunkMutation.applyTo`.
   - Commits then cost one `fdatasync` plus encoding: 15.7 ms p50 and 23.7 ms p99 on `millions`.
5. **A log must tell a torn tail from corruption.**
   - The first mutation log replayed up to the first invalid frame, treating it as a torn tail. The probe showed this silently dropping every later, acknowledged commit, in 300 of 300 trials on the read path. `verify()` caught all of them.
   - This is the rule ADR 0006 had already rejected for journals.
   - Frames now carry a header checksum over their length. Only an *incomplete* final frame is torn; anything else fails closed. A regression test (`deltaLogDamageBeforeTheEndIsCorruptionNotATornTail`) pins the rule. The re-run found 0 silent results.
6. **SQLite and MVStore grow large.** SQLite needs 48–117 bytes per record, about 5× C1. MVStore needs 22–59, and **275** on `millions`, where it reached 2.6 GB without compaction. Both also slow to about 200 ms per commit at 9.5 M records.
7. **Uniform random lookups are C1's weak spot at scale.** On `millions`, a cache miss inflates and walks a whole region block: about 60 µs p50 and 0.7 ms p95 on regions with many seasons of activity. SQLite (10 µs) and MVStore (7 µs) answer uniform lookups faster. Local lookups, which are what Paper does around players, take 0.3–0.4 µs on C1.
   - The identified fix, compressing per row of 32 chunks inside a block and storing the offset table instead of rebuilding it, is **not measured**. Prompt 09 must measure it before claiming B4.

## 5. Comparison

| Criterion | C1-log (recommended) | C1-segments | C2 SQLite | C3 Roaring + C1 | C4 MVStore |
|---|---|---|---|---|---|
| Exact semantics: multi-season, legacy, protection | ✅ differential-tested | ✅ | ✅ | ✅ | ✅ |
| Incremental updates | ✅ one `fdatasync` per commit; cost ∝ the batch | ❌ copy-on-write per region | ⚠️ 64–205 ms, rising with size | ❌ like C1-segments, plus a full bitmap rewrite | ⚠️ 31–194 ms, rising with size |
| Streaming and paging | ✅ one block per cursor | ✅ | ✅ (one statement per region column) | ✅ | ✅ |
| Crash consistency (B3) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Corruption detection (B2) | ✅ CRC on every block, log frame, and catalog | ✅ | ❌ silent wrong reads | ✅ | ❌ silent wrong reads |
| Repair boundary | one region (1 024 chunks) or one log frame; `verify()` names it | one region | page or file; tooling-dependent (`.recover`) | one region or the bitmap file | chunk or file; H2 `Recover` tool |
| Files and backups | a few append-only segments, a small log, and a catalog; rolled segments are immutable, so incremental backups copy only what's new | same, minus the log | one file mutated in place, plus `-wal`/`-shm` while open; backups need the backup API or a checkpoint | like C1 plus a bitmap file | one file mutated in place; chunks are reused, so every backup copies everything |
| Memory (index heap, `millions`) | 58 MiB, of which 48 MiB is the configurable block cache | 63 MiB | ~1 MiB of heap; the native page cache is extra | 84 MiB (the bitmaps add about 20 MiB) | 55 MiB |
| Point, rectangle, season, envelope queries | ✅ except uniform random lookups at 9.5 M (§4.3, item 7) | same | ⚠️ scans 0.18–1 M/s; envelopes 60–540 ms | same as C1 | ✅ |
| Disk per record | 5–20 bytes once checkpointed | 3–21 | ❌ 48–117 | 3–22 | ❌ 22–275 |
| Dependencies and maintenance | ✅ JDK only (`java.util.zip`, CRC-32C, `FileChannel`) | ✅ | ❌ native libraries per OS and architecture. Paper already puts sqlite-jdbc 3.49.1.0 on the server classpath, and JNI symbol names prevent relocating our copy. | ⚠️ one pure-Java dependency | ⚠️ one pure-Java dependency (MPL-2.0 or EPL-1.0) |
| Format longevity and migration | ⚠️ we own the format; it is small, documented, and readable in any language with DEFLATE and CRC-32C | ⚠️ same | ✅ SQLite's file format is extremely stable; schema migrations are SQL | ⚠️ ours, plus Roaring's portable spec | ❌ H2's storage format broke between 1.4 and 2.x |
| Readable diagnostic export | ✅ canonical JSONL (draft §9) | ✅ | ✅ also browsable with the `sqlite3` CLI | ✅ | ⚠️ through our export only |
| Code we must own | the most: format, log, checkpoint, compaction | less | least | most | little |

## 6. Recommendation and decision table

**Recommended: C1-log.** That is region-partitioned, compressed, CRC-checked record blocks in append-only segments; a catalog with derived summaries as the checkpoint; and an append-only, CRC-framed log of *mutations* as the commit path. It is specified in [`chunk-index-v1-draft.md`](../formats/chunk-index-v1-draft.md).

| | C1-files | C1-segments | **C1-log** | C2 SQLite | C3 Roaring | C4 MVStore |
|---|---|---|---|---|---|---|
| Gates B1–B3 | B2 ✅ and B3 ✅; B1 not run | ✅ | ✅ | ❌ B2 | ✅ | ❌ B2 |
| Durable commit | ❌ 2.7–6.6 s, even on small datasets | ❌ 5 s at 9.5 M | ✅ 15.7 ms p50 at 9.5 M | ⚠️ 205 ms at 9.5 M | ❌ 5.2 s at 9.5 M | ⚠️ 194 ms at 9.5 M |
| Disk efficiency | ✅ | ✅ | ✅ | ❌ | ✅ | ❌ |
| No new dependency | ✅ | ✅ | ✅ | ❌ native | ❌ | ❌ |
| Long-term format control | ✅ | ✅ | ✅ | ✅ | ⚠️ | ❌ |
| Engineering we must own | ⚠️ | ⚠️ | ❌ most | ✅ least | ❌ | ✅ |
| **Verdict** | reject: commits, build speed, file count | reject: superseded by C1-log | **recommend** | reject on B2 and the Paper classpath | reject: dominated by C1 | reject on B2 and format history |

**Why C1-log despite owning more code:**

- It is the only family that passes the corruption gate.
- Its commit cost is one `fdatasync` independent of index size.
- Its files are append-only, so backups and repairs are bounded and incremental.
- It adds no dependency.
- Its semantics come from the same `ChunkMutation.applyTo` that defines the contract, so the reference model checks it directly.

The extra code is bounded and mechanical (framing, CRCs, a catalog, a log, and checkpoints), and the spike has already exercised it under differential, torn-tail, crash, and corruption tests.

**What would change this recommendation:**

- If the owner prefers SQLite's tooling and longevity enough to wrap every row with our own checksum and a per-region count or digest table. Without that, B2 fails, and it must also cover rows that a damaged B-tree silently *omits*.
- If uniform random lookups at multi-million scale turn out to matter more than local ones and the per-row compression fix fails in Prompt 09.

### 6.1 Dependency and licensing consequences

| Option | Production dependency | License obligations | Other consequences |
|---|---|---|---|
| **C1-log (recommended)** | **none**: JDK only (`java.util.zip` DEFLATE, `java.util.zip.CRC32C`, `FileChannel`) | none beyond MCOneWorld's own MIT license | We maintain the format and its tooling |
| C2 SQLite | `org.xerial:sqlite-jdbc` | Apache-2.0 (driver) plus the public-domain SQLite library. Permissive. | Native binaries for every OS and architecture. Paper's server classpath already carries sqlite-jdbc 3.49.1.0, and JNI symbol names forbid relocating ours, so the plugin could not pin its own version (root §5). |
| C3 Roaring | `org.roaringbitmap:RoaringBitmap` | Apache-2.0. Permissive, with NOTICE text to carry. | Must be relocated when shaded into the Paper jar |
| C4 MVStore | `com.h2database:h2-mvstore` | MPL-2.0 or EPL-1.0. Weak copyleft: unmodified redistribution is fine, but modifications of its files must be published. | Must be relocated when shaded |

The spike's dependencies are on the `bench` classpath only. They are never packaged or shipped, so they create no obligation today; Prompt 09 removes them (§8).

## 7. Backup and corruption behaviour (recommended design)

- **Detection.** Every block, log frame, and catalog carries a CRC-32C. The catalog also binds each block's CRC to the checkpoint.
  - Readers fail with a coded error naming the region or frame.
  - `verify()` re-derives every summary from the blocks, and resolves every logged chunk.
- **Repair boundary.** A damaged block loses at most one region's provenance, and a damaged log frame loses at most one commit. Neither is ever silently replaced.
  - **For the owner:** recovery means restoring that region's block from a backup, or rebuilding the region with an explicit, journaled operation. It is never automatic, because provenance cannot be re-derived from the world.
- **Torn writes.** Only an *incomplete* final log frame is an unacknowledged commit, and it is ignored on open. A complete frame with a bad checksum, or a damaged frame header, is corruption and fails closed (§4.3, item 5). A torn log is never appended to: the next commit checkpoints first. Segments are never appended to across writer sessions.
- **Backups.**
  - Rolled segments never change, so incremental backups copy only new segments, the log, and the catalog.
  - A consistent online backup copies a writer-reported snapshot: the catalog generation, the referenced segments, and the log length.
  - Copying the directory naively while Paper writes is **not** guaranteed to be consistent; the backup operation (a later prompt) must use the snapshot.

## 8. Migration implications

- **New reserved area.** `chunk-index/` becomes a reserved, mutable sidecar area, like `operations/`.
  - This **reopens a Prompt 04 resolution**. `docs/formats/README.md` says indexes are referenced through `files` with role `chunk-index` and a checksum. A mutable index can't carry a whole-file checksum in the manifest.
  - Proposed: a later manifest minor version references the area by format, version, and index id. Operations and backups pin the catalog generation and log length instead.
  - No released manifest exists, so nothing needs migrating today. The manifest change itself must follow ADR 0005.
- **Format versions.** The index format is versioned independently: `mconeworld-chunk-index` 1.0.
  - Readers fail closed on an unknown major, and treat a newer minor as read-only.
  - Migrations between majors are explicit, journaled operations that build a **new** index beside the old one and verify it through the canonical export before switching. Test plan §3.
- **Spike removal.** The spike-only dependencies (sqlite-jdbc, RoaringBitmap, h2-mvstore) and the `bench` candidates are removed in Prompt 09. The harness, datasets, budgets, and probes are kept to measure the production implementation.

## 9. Owner decisions requested

| # | Decision | Recommendation |
|---|---|---|
| D1 | Accept C1-log as the chunk-index encoding (this ADR) | Accept |
| D2 | Accept that a mutable `chunk-index/` area is referenced by the manifest without a checksum, reopening the Prompt 04 "indexes are checksummed `files`" resolution | Accept. Pinning the generation gives operations the same change detection. |
| D3 | Accept B4 (uniform lookups at 9.5 M records) as an open target, to be met by per-row compression in Prompt 09, rather than a blocker | Accept |
| D4 | Remove the spike dependencies and candidates in Prompt 09, keeping the harness | Accept |

## 10. Consequences

- MCOneWorld owns a binary format and its corruption handling, so the test plan's exhaustive corruption, truncation, and fault-injection suites become mandatory in Prompt 09.
- Paper's index writer must checkpoint periodically, off the main thread. A checkpoint folding 25 000 mutations into 1 M records took 0.8–1.5 s, and on 9.5 M records 18.5 s. 25 000 uniformly scattered mutations touched nearly every one of 16 000 regions, rewriting about 150 MB. Localized updates touch far fewer regions, but Prompt 09 must make checkpoint and compaction scheduling explicit and measured.
- Readers pay for un-checkpointed mutations: a lookup of a logged chunk folds its mutations, and scans merge the log. That cost is bounded by the 100 000-mutation checkpoint threshold.
- Root §9's "compact chunk-index encoding" decision is proposed here and remains **undecided** until the owner accepts.

## 11. Testing

- **Already in `check`:**
  - the `sidecar.index` contract tests (`ChunkOrderTest`, `ChunkMutationTest`, `ReferenceChunkIndexTest`)
  - reference property tests (`ReferenceChunkIndexProperties`)
  - dataset tests (`SyntheticWorldTest`)
  - the candidate differential, snapshot, atomicity, torn-tail, and mid-log damage tests (`CandidateDifferentialTest`, 32 cases, in `benchTest`)
- **Prompt 09** executes [`chunk-index-test-plan.md`](../architecture/chunk-index-test-plan.md): exhaustive corruption, truncation, fault-injected `fsync`, golden fixtures, and migration tests. It also re-runs this harness against the production implementation.
