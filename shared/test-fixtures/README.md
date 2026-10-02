# shared:test-fixtures

Artifact: `io.github.kjmorse2.mconeworld:mconeworld-shared-test-fixtures` · Package: `io.github.kjmorse2.mconeworld.fixtures`

**Responsibility.** Test-only support:

- builders that make invalid cases explicit
- small, documented, legally redistributable canonical fixtures that tests copy before mutating
- golden files carrying format-version and checksum metadata
- contract assertions (`ModuleContract`)

**Use only from `testImplementation`.** Any production configuration that resolves this project fails `verifySharedDependencies`.

**May depend on:** `shared:domain`, `shared:sidecar`, `shared:protocol`.

**Forbidden:** platform dependencies. Never commit real user worlds, player data, server jars, or secrets.

**Current contents:**

- `ModuleContract`: every module's smoke test uses it to assert that the module's packages are `@NullMarked` and compiled to Java 21 bytecode.
- `DomainFixtures`: deterministic ids and instants.
- `ManifestFixtures`: the golden manifest.
- `OperationFixtures`: plans, event sequences, and the golden `operation/v1/plan-full.json` and `journal-rolled-back.jsonl`, each with a `.meta`.
- `fixtures.index` (Prompt 08):
  - `ReferenceChunkIndex`: the deliberately simple, in-memory `ChunkIndex`. It is the semantic reference, so never optimize it.
  - `ChunkIndexDifferential`: streams any implementation against the reference.
  - `ChunkDatasets` and `SyntheticWorld`: deterministic, seed-generated benchmark and test worlds, from `friend-server` to the 9.5-million-record `millions`. They are streamed one region at a time and never committed.
  - `ChunkRecordText`: canonical record lines and whole-index digests.
  - `IndexWorkloads`: bulk loads in region order, and runtime-like update batches that never get rejected.
  - `ChunkIndexFixtures`: the golden chunk-index v1 files (`chunk-index/v1/`: catalog, segment, log, export, each with a `.meta`) and the recipe that writes them.
- `fixtures.protocol` (Prompt 10): `ProtocolFixtures`, the seven golden protocol v1 vectors (`protocol/v1/*.bin`, each with a `.meta` naming channel, length, and SHA-256), their values, and `main`, which regenerates them for review.
