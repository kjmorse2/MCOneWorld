# `bench` source set: the Prompt 08 chunk-index spike

**Not production code.** This source set holds the four candidate chunk-index encodings and the benchmark harness behind [ADR 0007](../../../../docs/decisions/0007-chunk-index-encoding.md), which is **Proposed**. It is never packaged and never on a production classpath. Once ADR 0007 is decided, it is removed, or the chosen candidate is rewritten properly in `src/main` (Prompt 09).

| Package | Contents |
|---|---|
| `sidecar.index.spike` | The candidates. `Candidate` lists them: `c1-region-files`, `c1-region-segments`, `c2-sqlite`, `c3-roaring`, `c4-mvstore`. It also holds `Workload`, the shared load and update generators, and `CandidateDifferentialTest`. |
| `sidecar.index.spike.bench` | `BenchmarkDriver`, `BenchmarkCase`, `CorruptionProbe`, `CrashProbe`, and `Summarize` |

## Run

```bash
# Differential tests: every candidate must answer exactly like the reference index.
# This task is part of `check`.
./gradlew :shared:sidecar:benchTest

# Benchmarks. Each case runs in fresh JVMs; results are appended to
# docs/benchmarks/chunk-index/raw/<run>.jsonl
./gradlew :shared:sidecar:benchChunkIndex -Pbench.args="--datasets friend-server"
./gradlew :shared:sidecar:benchChunkIndex -Pbench.args="--datasets all --probes"
```

The formats, budgets, and results are in [`docs/benchmarks/chunk-index/`](../../../../docs/benchmarks/chunk-index/).

## Rules for this code

- The candidates share `SpikeIndexBase`, which provides batches and lifecycle, and `RecordCodec`, which provides record bytes. Storage engines therefore differ only in storage.
- A candidate's numbers count only while `CandidateDifferentialTest` passes for it.
- The on-disk layouts here are unversioned throwaway formats. The proposed production format is in [`docs/formats/chunk-index-v1-draft.md`](../../../../docs/formats/chunk-index-v1-draft.md).
