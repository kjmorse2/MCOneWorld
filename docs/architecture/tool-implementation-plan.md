# MCOneWorld Tool v1: implementation plan

- Status: **approved** by the project owner, 2026-10-02. Milestones T0–T15 below are the Tool's prompt sequence, done in order, each gated by its tests.
- Decisions: ADR [0009](../decisions/0009-tool-and-mca-selector.md) (MCA Selector), [0010](../decisions/0010-end-reset-by-archive.md) (End reset), [0011](../decisions/0011-offline-season-activation.md) (offline activation), [0012](../decisions/0012-backup-format.md) (backups).
- Module charter: [`tool/AGENTS.md`](../../tool/AGENTS.md).

## Context

The shared libraries are finished (release candidate `shared 0.1.0-rc.1`; see `docs/architecture/shared-release-audit.md`). They cover:

- the sidecar (manifest, locking, transactions, migrations, recovery)
- seasons and the archive state
- the chunk index
- operation plans and journals
- coordinate math

What they deliberately lack is everything that touches Minecraft world files: region headers, `level.dat`, dimension layout, `session.lock`, backups of world files, and the End archive. That layer, the season workflows, and the JavaFX GUI on top of them make up MCOneWorld Tool.

**The owner's workflow:**

1. Stop the server.
2. Optionally trim the world with MCA Selector to reduce its size. (The owner calls it "MCEdit"; the app in `offline-tool/` is MCA Selector 2.9.)
3. Open the world in MCOneWorld Tool.
4. Reconcile the sidecar with the world.
5. Back up.
6. Create the next season: a far-away origin and spawn, a fresh End, and activation.
7. Start the server; the plugin applies the new season.

**Owner decisions (2026-10-02)**, recorded as ADRs 0009–0012:

| Topic | Decision |
|---|---|
| MCA Selector relationship (root §22) | **Vendor plus new app.** New tool modules in the root build, under its quality gates. Only the needed MCA Selector code is copied into an attributed module. `offline-tool/` stays untouched; it remains the user's trimming tool and the upstream reference. |
| Minecraft versions | Read-only inspection for any version (region headers are version-agnostic). World mutation, which is only the End archive (the dragon fight is saved inside the End directory in 26.1+), runs only on the pinned **26.2 (data version 4903)** and fails closed otherwise. |
| v1 scope | Sidecar setup with legacy import; reconcile after external trimming; backup creation and verification; seasons (plan, create, offline activation); End archive and regeneration; a map for planning. **Out of scope:** restore (except operation rollback), chunk deletion (lowest priority), strongholds, season-local coordinates (the tool shows absolute coordinates only). |
| Activation | **The tool activates offline.** The sidecar records "activation pending application" so the plugin applies spawn and player resets at the next start. This needs a shared manifest format change. |
| Removed chunks found by reconcile | Removed from the index, but every removed record is first written to a report under `reports/`. A removed protected chunk blocks until acknowledged. |
| Backups | Live in the sidecar under `backups/<id>/` (accepted manifest format), with checksums, verified after writing. |
| End strategy (root §22; amends §3's "preserve the End") | At each season creation, archive the whole End dimension directory (move its files into the backup area). In 26.1+ that includes the dragon fight (`…/the_end/data/minecraft/ender_dragon_fight.dat`), so Minecraft generates a fresh End and fight. Pinned version only, server stopped, full safe protocol. Root AGENTS.md §3 is updated to reference the ADR. |
| Java | Java 25 with JavaFX 25. The installer bundles its own runtime through jpackage. The shared jars (Java 21 bytecode) are used unchanged. |

## Architecture

New root-build modules (Kotlin DSL; locked and verified; Spotless, Error Prone, and NullAway; MIT headers):

```text
tool/
  world/   :tool:world  read-only world access + the narrow End-archive writer.
                        Vendored MCA Selector pieces (MIT, Querz) with an upstream-commit note.
                        Depends on shared:domain. No JavaFX.
  core/    :tool:core   headless application services and a CLI. Depends on tool:world and
                        shared:{domain,sidecar}. No JavaFX.
  app/     :tool:app    JavaFX 25 UI (views plus testable view-models). Depends on tool:core only.
```

Dependency direction is `app → core → world → shared`, enforced by extending `verifySharedDependencies`, or by a new tool guard with the same mechanism (in `build-logic`). The shared modules never depend on tool modules.

**Shared code to reuse** (`shared/*/src/main/java/io/github/kjmorse2/mconeworld/...`):

- **Storage:** `sidecar.storage.SidecarRoot`, `SidecarStore` (`open`, `inspect`, `readManifest`), `SidecarWriter` (`begin`, `recover`, `openIndexWriter`, `operationFiles`), `SidecarTransaction` (stage plus commit), `RecoveryReport`, `LockException` / `StaleLockPolicy`.
- **Manifest and migrations:** `sidecar.manifest.ManifestDocument` and `ManifestCodec`; `sidecar.migration.MigrationPlanner` and `MigrationRunner` (offered explicitly when a manifest is older).
- **Archive and seasons:** `domain.archive.ArchiveState` (`appendSeason`, `activate`, `archiveActiveSeason`, `cancelSeason`, `renameSeason`, `withBackup`, `withProtectedRegion`, `validate`); `domain.season.SeasonRecord.planned` and `SeasonStatus`.
- **Coordinates:** `domain.coordinate.DimensionOrigin`, `OriginAlignment.recommendedFor`, `OverworldNetherScaling`, `WorldBounds`, `ChunkPosition`, `RegionPosition`, `BlockPosition`.
- **Chunk index:** `sidecar.index.ChunkIndexStore` (`create`, `openWriter`, `openReader`, `inspect`, `verify`), `SidecarChunkIndex`, `ChunkMutation.ObserveFirst` / `Remove` / `SetProtection`, `ChunkQuery` (`within`, season filter), `ChunkIndexReader.envelope`, `ChunkIndexExport.recordLine` (for the removed-chunk reports), and `ChunkIndexState.pin()` with `INPUT_KEY` (plan preconditions).
- **Operations:** `domain.operation.OperationPlan`, `OperationType` (`mconeworld:…` keys), `JournalEvent`, `RollbackDeclaration`, `ImpactClass`; `sidecar.operation.OperationJournals` (`create`, `open`, `inspect`, `discardTornTail`) and `OperationJournal.append`.
- **Validation:** `domain.validation.ValidationResult` and `ValidationIssue`, used for every report shown in the UI.

**Vendored from `offline-tool/`** (copied, then refactored to drop the global `ConfigProvider` and `JobHandler` state):

- `io/mca/MCAFile` header and timestamp reading (`loadHeader`)
- `io/FileHelper.detectDimensionDirectories` (both the legacy `DIM-1` and the 26.1+ `dimensions/minecraft/*` layouts)
- `io/WorldDirectories` and `io/RegionDirectories`

Later and optional: `tile/*` and `version/*` terrain rendering.

**NBT:** read-only only. The Tool reads `level.dat` (data version, level name, seed) and never writes NBT, so T1/T2 add a small bounded NBT reader of our own, which limits decompression, depth, and sizes (root §20). There is no dependency, and MCA Selector's JitPack NBT isn't needed.

## Milestones

Each milestone is one or more commits. Its tests must pass in `./gradlew check`; nothing is pushed.

**T0: Decisions and module charter**

- ADRs:
  - 0009: MCA Selector relationship (vendor plus new app)
  - 0010: End reset (archive the whole End dimension directory, dragon fight included)
  - 0011: offline activation with pending application
  - 0012: backup format
- Root AGENTS.md §3 and §22 amended to point at ADR 0010 and resolve the two deferred items.
- `tool/AGENTS.md` (the module charter: read-only by default, the only world-mutating operations, pinned-version gate) and `tool/README.md`.
- **Done when:** the docs are committed; no code yet.

**T1: Build scaffold**

- `:tool:world`, `:tool:core`, and `:tool:app` in `settings.gradle.kts`, with a `mconeworld.tool-module` convention plugin (release 25).
- JavaFX 25 from Maven Central, with Linux, Windows, and macOS classifier artifacts all locked and verified.
- `toolCheck` aggregate task; CI job.
- `app` shows an empty window. A headless smoke test (TestFX with Monocle, locked) proves startup.
- **Done when:** `./gradlew toolCheck` passes and `git diff --stat offline-tool/` is empty.

**T2: Read-only world access (`:tool:world`)**

- `WorldLocator` validates a world directory. `LevelData` reads `level.dat` (data version, level name, seed) through a bounded read-only NBT reader. `DimensionLayout` handles the legacy and 26.1+ vanilla layouts, Paper's per-dimension world folders (verified against Paper 26.2), and custom datapack dimensions.
- `RegionInventory` streams chunk presence and header timestamps per dimension for region, entities, and poi files.
- `SessionLockProbe` detects a running server through the `session.lock` file lock. `WorldSupport` classifies a world as `INSPECT_ONLY` or `MUTABLE` (pinned version only).
- Test support: a synthetic world builder that writes `.mca` headers and `level.dat` (no Mojang assets).
- **Tests:**
  - negative and extreme coordinates
  - corrupt or truncated headers and `.mcc` references
  - missing poi or entities files
  - both layouts
  - a held `session.lock`
  - bounded memory on a large synthetic world
- **Done when:** inventory counts match the builder's ground truth exactly.

**T3: Session and status (`:tool:core`)**

- `ToolSession.open(world, sidecarRoot)` gathers:
  - the world inspection
  - `SidecarStore.inspect()` and `RecoveryReport`
  - `ChunkIndexStore.inspect()`
  - `OperationJournals.list()` with the status of each journal
  - manifest version and migration need
  - world-binding match
- It produces one `SessionStatus`, built from `ValidationIssue`s with remediation text.
- The writer lock is taken only for mutations, and is refused while the server holds it.
- Recovery and migration run only on explicit request, never automatically.
- **Tests:** every status combination, built with the shared fault filesystems.

**T4: Shared format additions** (in `shared/`, with the full Prompt-04/06 discipline)

- Manifest **1.1** (additive minor version):
  - a world binding (level name, seed hash, data version at import)
  - per-season activation state `pendingApplication`
  - archived-dimension records
- Migration 1.0 → 1.1, golden fixtures, the format spec, and `shared-contracts.json`.
- Backup format `mconeworld-backup` 1.0: a backup manifest listing relative paths, sizes, and SHA-256s, with kind `full` or `dimension-archive`. A `BackupFileWriter` reserved-area writer, modelled on `IndexFileWriter`, plus a verification routine.
- **Tests:** codec, golden, migration, and fuzz tests, as in the existing suites.

**T5: Initialize a sidecar with a legacy import** (operation `mconeworld:sidecar_init`)

- Steps:
  1. Plan: targets are the dimensions; preconditions are the world fingerprint, an unlocked world, and an empty sidecar.
  2. Preview: chunk counts per dimension.
  3. Apply: manifest revision 0 with the world binding and an empty `ArchiveState`; `ChunkIndexStore.create`; batched `ObserveFirst(Legacy, WORLD_IMPORT)` for every chunk; checkpoint.
  4. Validate: the index count equals the inventory, and `verify()` passes.
  5. Commit and complete the journal.
- World files are never touched (impact class: sidecar only).
- **Tests:** every journal step interrupted and recovered (reusing the patterns of `ChunkIndexFaultTest` and `CommitFaultTest`); a million-chunk synthetic import within the benchmarked budget.

**T6: Reconcile after external trimming** (operation `mconeworld:reconcile`)

- Diffs the region inventory against the index:
  - **New chunks** are recorded with low confidence: as in-season for the active season if there is one, legacy otherwise.
  - **Removed chunks** are exported to `reports/reconcile-<opId>.jsonl` with `ChunkIndexExport.recordLine`, then removed. A removed protected chunk blocks until acknowledged.
- The preview groups changes by season and dimension.
- **Tests:** add, remove, and protected cases; idempotence (a second reconcile changes nothing); interruption.

**T7: Backups** (operation `mconeworld:backup`)

- Copies the world directory (excluding `session.lock`) and the sidecar's authoritative files into `backups/<id>/`. Preconditions: a free-space check and the server stopped.
- Writes the backup manifest, re-reads every file to verify it, then records a `BackupRecord` through a manifest transaction.
- The copy can be cancelled. A partial backup is detected and never recorded.
- **Restore is deferred.** The UI shows exact manual restoration steps.
- **Tests:** corruption detected by verify, interruption, a full disk, symlink refusal.

**T8: Season planner (pure core)**

- `SeasonPlanner` proposes an overworld origin:
  - beyond all known chunks: the inventory plus `envelope()` for every season, with a configurable margin
  - 128-aligned (`OriginAlignment`)
  - within `WorldBounds` with headroom
- It also gives a matching Nether origin (÷8, aligned) and the default spawn (x, z) at the origin.
- The End gets no origin; it is regenerated instead.
- A manual pick is validated the same way and returns structured issues.
- **Tests:** property tests (never overlapping existing chunks or envelopes, always aligned, Nether scaling exact, bounds respected).

**T9: Season transition** (operation `mconeworld:season_transition`, the main workflow)

- **Preconditions:** pinned data version (`MUTABLE`), world unlocked, sidecar clean, no unfinished operations, a reconcile that is current (a pinned index state), and a verified backup no older than this plan.
- **Plan and preview** show:
  - the new season (name, origins, spawn)
  - the End files to archive (list and sizes)
  - the End dimension directory to archive (in 26.1+ it includes the dragon fight)
  - the previous season being archived
- **Apply**, in this order:
  1. Copy the End dimension's files into `backups/<id>/end/`, then verify. This is a copy-verify-delete, so it is safe across filesystems.
  2. Delete the originals. On Paper's separate world folder, only the dimension's data directory goes; folder-level files such as its `level.dat` stay.
  3. Remove the End's provenance from the index, with a report.
  4. In one manifest transaction: `appendSeason`, `archiveActiveSeason`, `activate`, `pendingApplication = true`, and the archived-dimension record. This transaction is the commit point.
- **Validate:**
  - the End is absent
  - every file outside the End dimension directory, `level.dat` included, is byte-identical
  - the index verifies
  - `ArchiveState.validate()` passes
- **Rollback** (from the journal) restores the End dimension directory from the operation's own verified copy.
- **The dragon-fight location** (owner-provided: inside the End directory in 26.1+) **and Paper's dimension-folder layout are confirmed on the pinned servers** by a recorded manual check before this milestone relies on them (root §18).
- **Tests:**
  - interruption at every step proves old-or-new-plus-rollback
  - wrong data version refused
  - running server refused
  - files outside the End directory unchanged

**T10: JavaFX shell and session UI (`:tool:app`)**

- MVVM: views in plain Java code with CSS adapted from MCA Selector's `base.css` (attributed); view-models free of JavaFX, so they can be unit tested.
- A `TaskRunner` maps core services, with progress and cancellation, onto `javafx.concurrent.Task`.
- Screens:
  - an Open dialog: world directory plus sidecar directory, defaulting to a sibling `mconeworld-data`; recent worlds stored in a per-user config, never in the sidecar
  - a Status dashboard: support badge, server lock, sidecar findings with remediation, and explicit "Recover…" and "Migrate…" actions
- **Tests:** view-model unit tests and Monocle smoke tests.

**T11: Map view**

- A canvas map with pan and zoom (the approach of MCA Selector's `TileMap`, reimplemented small), a dimension switcher, and the absolute coordinates under the cursor.
- Layers:
  - chunk presence from the inventory
  - provenance colours (legacy versus each season)
  - season envelopes
  - protected regions
  - existing origins and spawns
  - the proposed origin with its margin
- Level of detail: per-region aggregates when zoomed out. Data comes from a core `MapModel` that queries the index snapshot per visible region (`ChunkQuery.within`), with a bounded cache.
- **Tests:** `MapModel` unit tests (negative coordinates, region edges); a render smoke test.

**T12: Wizards and panels**

- **Initialize sidecar** (T5) and **Reconcile** (T6): scan, preview, confirm, progress, report.
- **New season wizard:** reconcile, then backup, then plan origins (suggest, or pick on the map with live validation), then name and spawn, then an End archive preview, then confirmation (typing the season name), then progress, then the validation report.
- **Panels:** Seasons (list; rename or cancel planned seasons), Backups (create, verify, list), Operations (journals, recommendations, explicit torn-tail discard), Reports viewer.
- **Tests:** wizard view-model state machines; one end-to-end smoke test on a synthetic world.

**T13: Headless CLI**

- `mconeworld-tool inspect|init|reconcile|backup|season plan|season create`, built on the same core services, with dry run as the default.
- It is used for end-to-end tests and automation.
- **Tests:** a CLI round trip on a synthetic world.

**T14: Packaging and user guide**

- jpackage artifacts `MCOneWorld-Tool-<version>.<dist>` for Linux, Windows, and macOS.
- Bundled licences: MCOneWorld MIT, MCA Selector MIT for the vendored code, and JavaFX (GPL-2.0 with Classpath Exception).
- A user guide covering prerequisites, preview, backup, execution, validation, and rollback for every operation (root §23).
- A recorded manual validation on a disposable copy of a real 26.2 world.

**T15 (lowest priority, optional)**

- Terrain rendering as a map background, vendoring MCA Selector's `version/*` renderers and colour mappings, with its cache in the OS cache directory.
- Chunk deletion through the vendored region writer, under the full safe protocol, pinned version only.

## Verification

- **Per milestone:** `./gradlew sharedCheck toolCheck` (format, Error Prone and NullAway, architecture guards, unit, property, and fault tests).
- **End to end:**
  1. CLI on a synthetic world: `init` → `reconcile` (after deleting region files to simulate trimming) → `backup` → `season create`.
  2. Assert the manifest, index, reports, backup verification, the End archive, and that every file outside the End directory is unchanged.
  3. Repeat with injected interruption at every journal step.
- **GUI:** Monocle smoke tests, plus a manual checklist on a disposable copy of a real 26.2 world:
  1. open the world
  2. initialize the sidecar
  3. trim with MCA Selector
  4. reconcile
  5. back up
  6. create season 2
  7. start Paper 26.2 and confirm a fresh End generates and the sidecar shows season 2 active, pending application
- **Invariants checked throughout:** `git diff --stat offline-tool/` is empty; no world write happens without a verified backup.

## Risks and dependencies

- **Plugin coordination.** Activation and the End reset change what the plugin must do at startup: apply the spawn and player resets when `pendingApplication` is set, and expect a fresh End. Both are recorded in ADRs 0010 and 0011 for the Paper project. The plugin itself is out of scope here.
- **Dragon fight and Paper layout.** The owner reports that 26.1+ keeps the dragon fight inside the End dimension directory, and that Paper keeps each dimension in its own world folder. Both are confirmed in T2 (layout) and before T9 (fight), never guessed. A version that keeps the fight in `level.dat` stays inspect-only.
- **JavaFX's per-platform artifacts** must be locked and verified for every OS in T1, because the repository rules allow mavenCentral only.
- **Disk space.** A full-world backup per season can be large. T7 checks free space and shows the expected size in the preview.
