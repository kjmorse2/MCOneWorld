# `bench` source set: the chunk-index benchmark harness

The harness behind [ADR 0007](../../../../docs/decisions/0007-chunk-index-encoding.md) and its budgets ([`docs/benchmarks/chunk-index/`](../../../../docs/benchmarks/chunk-index/)).

- **Prompt 08** built it to compare candidate encodings.
- **Prompt 09** removed the candidates and their dependencies (owner decision D4). It now measures only the production index, `sidecar.index.SidecarChunkIndex`.

The source set is never packaged and never on a production classpath.

| Type (package `sidecar.index.bench`) | Role |
|---|---|
| `Candidate` | The measured configurations: `prod-rows1`, `prod-rows4` (default), and `prod-rows32`. They differ only in rows per compression unit. |
| `ProductionIndex`, `BenchIndex` | A sidecar, its writer lock, and its index as one handle. Provably stale locks left by the crash probe's kills are taken over. |
| `BenchmarkDriver` | Runs every case in fresh, fixed-heap JVMs and appends raw result lines |
| `BenchmarkCase` | One case: a build phase, then the query and update phase |
| `CorruptionProbe`, `CrashProbe` | Gates B2 (random byte flips) and B3 (`SIGKILL` mid-commit) |
| `Summarize` | Raw results to Markdown; reads Prompt 08 files too |

Datasets and workloads come from `shared:test-fixtures` (`ChunkDatasets`, `IndexWorkloads`).

## Run

```bash
./gradlew :shared:sidecar:benchChunkIndex -Pbench.args="--datasets all --probes"
./gradlew :shared:sidecar:benchChunkIndex -Pbench.args="--candidates prod-rows4 --datasets millions"
./gradlew -q :shared:sidecar:benchSummarize -Pbench.args="docs/benchmarks/chunk-index/raw/<run>.jsonl"
```

Correctness is not this source set's job. The production index's differential, fault, and corruption tests live in `src/test` and run in `check`.
