# Chunk-index budgets (set before measuring)

- Milestone: Prompt 08 (chunk-index ADR and spike)
- Written: 2026-09-30, **before any candidate was implemented or measured**. The commit that adds this file precedes every benchmark result in history. A budget changed after measuring must say so and why.
- Used by: [ADR 0007](../../decisions/0007-chunk-index-encoding.md), and the benchmark harness, which reports each budget as `pass` or `fail`

## Why these budgets

The index stores chunk provenance for long-lived worlds. A world can hold millions of chunks, for example after a pre-generated 32 000-block square or years of elytra exploration.

Two products use it:

- **Paper.** The plugin runs inside a game server that shares its heap and disk with Minecraft. It records observations in small batches off the main thread, and it answers lookups for features such as protection checks.
- **The offline tool.** It imports worlds, previews plans over large areas, and exports diagnostics.

The budgets therefore bound memory hard, and they bound latency for the operations each product performs often.

## Reference conditions

- Hardware: AMD Ryzen 7 7800X3D (8 cores), 30 GiB RAM, NVMe SSD with ext4, Linux 7.2, JDK 25.0.4.1
- The OS page cache is warm. Dropping it needs root, so "cold open" means a new JVM and process, not a cold disk.
- The largest dataset (`millions`) runs with `-Xmx256m`. The other datasets run with `-Xmx512m`.
- Each (candidate, dataset) pair runs in its own JVM.

## Datasets

The datasets are synthetic, deterministic, and generated from a seed, so no world data is committed. The generator defines the exact shapes; the target sizes below are fixed now.

| Dataset | Target records | Represents |
|---|---|---|
| `friend-server` | ~25 000 | Three seasons on a small server, with a little Nether |
| `sparse-exploration` | ~1 000 000 | A long-lived world explored along long, thin travel paths, so many regions are only partly filled |
| `dense-square` | ~1 000 000 | A pre-generated 1 024 × 1 024-chunk square imported as legacy chunks |
| `multi-season` | ~1 000 000 | Ten seasons with distant origins |
| `negative-coords` | ~100 000 | Negative coordinates only, including region boundaries and the extreme `int` chunk range |
| `revisited` | ~500 000 | Six seasons, each revisiting earlier seasons' areas, so chunks have several activity entries |
| `millions` | ~10 000 000 | A mix of all of the above, run under `-Xmx256m` |

## Budgets

The **gate** budgets decide whether a candidate is acceptable at all. **Target** budgets rank acceptable candidates.

| # | Metric | Budget | Kind | Reason |
|---|---|---|---|---|
| B1 | Peak heap, any workload, `millions` dataset | Completes under `-Xmx256m` | gate | The index must never hold all records. A Paper server's heap belongs to the game. |
| B2 | Corruption probe: single-byte flips in index files | 0 silent wrong results. Every flip is detected or provably harmless (the full export is identical). | gate | Root `AGENTS.md`: never hide corruption behind defaults |
| B3 | Crash probe: process killed during update batches | 100 % of kills reopen to the last acknowledged state or the state being committed, never a mixture | gate | Crash consistency |
| B4 | Point lookup (hit or miss), warm | p50 ≤ 20 µs, p99 ≤ 1 ms | target | Runtime checks per chunk event |
| B5 | Small rectangle scan (16 × 16 chunks), warm | p50 ≤ 2 ms | target | Map tiles and protection previews |
| B6 | Large rectangle, season, and full scans | ≥ 500 000 records/s | target | 10 million records exported or previewed in ≤ 20 s |
| B7 | Season envelope, `millions` dataset | ≤ 2 s | target | Planning UI. It may be precomputed, but it must stay derived. |
| B8 | Durable commit of a 1 000-mutation batch | p50 ≤ 100 ms, p99 ≤ 500 ms | target | Asynchronous flushes from Paper |
| B9 | Bulk build (region-ordered import, committed every 100 000 mutations) | ≥ 100 000 records/s | target | Importing a large vanilla world |
| B10 | Cold open, `millions` dataset | ≤ 1 s | target | Plugin start-up |
| B11 | Disk size | ≤ 24 bytes per record on `sparse-exploration` and `dense-square` | target | Backups copy the index with the world |
| B12 | File count | ≤ one file per touched 32 × 32 region, plus a constant | target | Comparable to the world's own region files. Backup tools handle that. |

## What the budgets deliberately do not cover

- **Power-loss durability.** Killing a process leaves the OS page cache intact, so the crash probe tests atomicity, not `fsync` ordering. `fsync` ordering is reviewed by design and covered by fault injection in Prompt 09, as for the journal (ADR 0006).
- **Windows and macOS.** Only the reference conditions above were measured. A platform claim needs its own measurement.
- **Concurrent writers.** The sidecar lock (ADR 0004) admits one writer, so only single-writer, multi-reader behaviour is measured.
