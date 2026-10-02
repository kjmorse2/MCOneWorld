# Chunk-index benchmarks (Prompt 08)

The evidence behind [ADR 0007](../../decisions/0007-chunk-index-encoding.md).

| File | What it is |
|---|---|
| [`budgets.md`](budgets.md) | Gate and target budgets, and the datasets. Committed **before** any measurement (`ab5a0fa0`). |
| [`results.md`](results.md) | Prompt 08: the candidate comparison (budget verdicts, per-dataset tables, observations) |
| [`results-prompt09.md`](results-prompt09.md) | Prompt 09: the production index against the Prompt 08 baseline, including the choice of compression unit |
| `raw/<run>.jsonl` | Raw results, one JSON object per line. Each file is under 200 KB, so they are committed. Generated index files never are. |

The harness is in `shared/sidecar/src/bench/` (see its [README](../../../shared/sidecar/src/bench/README.md)). Since Prompt 09 it measures only the production index; the Prompt 08 spike candidates were removed (ADR 0007, D4), and their raw results stay here. The datasets are `ChunkDatasets` in `shared:test-fixtures`.

## Reproducing

```bash
./gradlew :shared:sidecar:test           # the production index must match the reference first
./gradlew :shared:sidecar:benchChunkIndex -Pbench.args="--datasets all --probes"
./gradlew -q :shared:sidecar:benchSummarize -Pbench.args="docs/benchmarks/chunk-index/raw/<run>.jsonl"
```

Index files are written under `shared/sidecar/build/chunk-index-bench/`. That location must be on the filesystem you want to measure: a RAM-backed `/tmp` would hide every `fsync`. The full run needs about 3 GB of free space at peak and takes about ten minutes for the three production layouts on the reference machine.

### How each case runs

1. A **build JVM** bulk-imports the dataset in region order, committing every 100 000 records, then closes the index and measures disk usage.
2. A fresh **query JVM** then runs these steps:
   1. It opens the index. The first open is reported as `open.cold`, which includes class loading; a reopen is `open.warm`.
   2. It runs 100 000 timed lookups after a 20 000-lookup warm-up. Positions are `uniform` (anywhere near explored terrain) or `local` (a walk that stays near the previous position).
   3. It times 2 000 scans of 16 × 16 chunks and 20 scans of 512 × 512 chunks.
   4. It scans each of the first six seasons with `touchedBy`, and times each season's `envelope` and `count` with `firstObservedIn`.
   5. It runs a full scan.
   6. It commits 25 durable update batches of 1 000 mutations; the first 5 are warm-up. The mix is 70 % activity, 20 % first observations, 5 % protection changes, and 5 % removals.
   7. It runs the candidate's full `verify()`.
3. The `millions` dataset runs with `-Xmx256m` (budget B1). Every other dataset runs with `-Xmx512m`.

All inputs are generated before timing starts.

**Probes** (`--probes`) run on `friend-server`:

- `CorruptionProbe` performs 300 single-byte flips at random offsets, each on a fresh copy.
- `CrashProbe` runs 40 `SIGKILL`s of a writer in the middle of a commit.

## Raw result format (`mconeworld-bench-result`, version 1)

One compact JSON object per line. Lines are only ever appended, so a file can hold several runs.

| Member | Meaning |
|---|---|
| `format`, `version` | Always `"mconeworld-bench-result"` and `1` |
| `run` | `run-<UTC time>-<commit>[-dirty]`. `-dirty` means `shared/` or `gradle/` had uncommitted changes. |
| `candidate` | Prompt 09: `prod-rows1`, `prod-rows4`, `prod-rows32`. Prompt 08 files: `c1-region-files`, `c1-region-segments`, `c1-region-log`, `c2-sqlite`, `c3-roaring`, `c4-mvstore`. |
| `dataset`, `scale` | The `ChunkDatasets` name and its linear scale |
| `phase` | `env`, `build`, `query`, `corruption`, `crash`, or (for status lines) the child's phase |
| `metric`, `unit` | See below |
| `value` | The primary value, for single-valued metrics |
| `n`, `p50`, `p95`, `p99`, `max`, `mean` | Latency distributions, in nanoseconds (unit `ns`). Percentiles are nearest-rank. |
| `records`, `seconds` | Rate metrics (unit `records/s`) |
| `hitRate`, `queries`, `batches` | Context for a latency metric |

The `env` line has no `candidate`. It records the commit, the JVM, the OS, the CPU, the processor count, and `fsyncMillisP50`: the median time to write and `fsync` 4 KiB in the work directory, which is the floor under every durable commit.

**Metrics:**

| Area | Metrics |
|---|---|
| Build | `build.records`, `build.seconds`, `build.rate`, `world.regions` (regions with candidate chunks: an upper bound on touched regions) |
| Disk | `disk.bytes`, `disk.files`, `disk.bytesPerRecord`; after the update batches, `disk.afterUpdates.bytes` and `disk.afterUpdates.files` |
| Open | `open.cold`, `open.warm` (ms) |
| Lookups and scans | `lookup.uniform`, `lookup.local`, `scan.rect16` (latency); `scan.rect512`, `scan.season`, `scan.full` (rates); `envelope.season`, `count.season` (latency) |
| Updates and verify | `update.batch1000` (latency of apply plus durable commit); `checkpoint.seconds` (C1-log's explicit checkpoint after the updates); `verify.seconds` |
| Memory | `heap.indexRetained` (live heap held by the open index after every workload, measured after full GCs), `heap.peakUsed` (sampled used heap, including garbage), `rss.peak` (the process's VmHWM), `heap.max` |
| Corruption probe | `corruption.{read,verify}.{detected,crashed,harmless,silent}`, `corruption.trials`, `corruption.bytes`, `corruption.silentExamples` |
| Crash probe | `crash.trials`, `crash.acknowledgedState`, `crash.pendingState`, `crash.inconsistent`, `crash.openFailed`, `crash.commitsBeforeKill`, `crash.problems` |
| Status | `case.status`: `ok`, or `failed:<exit code> <last log lines>` (for example, an `OutOfMemoryError` under B1) |
