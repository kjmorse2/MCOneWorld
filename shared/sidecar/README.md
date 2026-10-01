# shared:sidecar

Artifact: `io.github.kjmorse2.mconeworld:mconeworld-shared-sidecar` · Package: `io.github.kjmorse2.mconeworld.sidecar`

**Responsibility.** Everything under the `mconeworld-data/` directory next to a world save:

- the deterministic JSON manifest codec (ADR 0003)
- explicit schema migrations
- storage safety: atomic writes, checksums, locking, path confinement
- the `ChunkIndex` abstraction

It operates on a caller-supplied root and never rewrites anything on a read-only open.

**May depend on:** `shared:domain`, and internal-only libraries accepted by ADR. These must never appear in the public API.

**Forbidden:** `shared:protocol`, `shared:test-fixtures` in production code, and every platform dependency.

**Status:** manifest v1 (Prompt 04), storage safety (Prompt 05), the migration framework (Prompt 06), and operation plans and journals (Prompt 07) are implemented. The chunk-index **contract** (`sidecar.index`) exists. Its persistent encoding is proposed in ADR 0007, awaits owner review, and will be implemented in Prompt 09.

Packages:

- `sidecar.manifest` (API): `ManifestCodec`, `ManifestDocument`, `DecodedManifest`, `ManifestException`, `ManifestProblemKind`, `ManifestIssueCodes`, `ManifestLimits`, `SchemaVersion`, `FileReference`, `FeatureKey`, `FeatureRecord`, `PreservedMembers`, `ManifestFiles`. The format is [`docs/formats/manifest-v1.md`](../../docs/formats/manifest-v1.md).
- `sidecar.storage` (API):
  - paths: `SidecarPath`, `UnsafeSidecarPathException`, `SidecarRoot`
  - store, writer, and transactions: `SidecarStore`, `SidecarWriter`, `SidecarTransaction`, `StagedFile`, `CommitResult`
  - recovery: `RecoveryReport`, `RecoveryFinding`
  - locking: `SidecarLock`, `LockStatus`, `LockInfo`, `LockOwner`, `StaleLockPolicy`, `LockException`
  - utilities and errors: `Checksums`, `StorageException`, `StorageIssueCodes`
  - fault-injection seam: `StorageFileSystem`, `JdkStorageFileSystem`, `CommitStage`
  - the mutable `operations/` area: `OperationFiles` (read), `OperationFileWriter` (write-once, append, replace; lock-checked)

  The layout and protocol are in [`docs/formats/sidecar-storage-v1.md`](../../docs/formats/sidecar-storage-v1.md); the policy is in ADR 0004.
- `sidecar.migration` (API): `MigrationPlanner`, `MigrationRunner`, `MigrationRegistry` (the built-in registry is empty), `ManifestMigration`, `MigrationStep`, `MigrationId`, `MigrationPlan`, `MigrationReport`, `StepReport`, `MigrationRecorder`, `VerifiedBackup`, `MigrationResult`, `MigrationException`, `MigrationIssueCodes`. The policy is in ADR 0005.
- `sidecar.operation` (API): `OperationJournals`, `OperationJournal`, `JournalAck`, `MutationPermit`, `OperationStatus`, `JournalIntegrity`, `OperationPlanCodec`, `DecodedPlan`, `OperationPlanLimits`, `OperationFormatException`, `JournalException`, `JournalIssueCodes`. The format is [`docs/formats/operation-journal-v1.md`](../../docs/formats/operation-journal-v1.md); the policy is ADR 0006.
- `sidecar.index` (API only; Prompt 08): `ChunkIndex`, `ChunkIndexReader`, `ChunkCursor`, `ChunkIndexBatch`, `ChunkMutation`, `ChunkQuery`, `SeasonFilter`, `ProtectionFilter`, `ChunkOrder`, `ChunkIndexException`, `ChunkIndexIssueCodes`. There is no persistent implementation. The in-memory reference is `fixtures.index.ReferenceChunkIndex`.
- The `bench` source set (**spike, not API, never packaged**) holds the Prompt 08 candidate encodings and the benchmark harness. See [`src/bench/README.md`](src/bench/README.md).
- `sidecar.internal.json` (**internal, not API**): the strict JSON reader over jackson-core, and the canonical writer.

jackson-core is an `implementation` dependency. Its types never appear in public signatures. Platform jars that shade it must relocate `tools.jackson.core`.
