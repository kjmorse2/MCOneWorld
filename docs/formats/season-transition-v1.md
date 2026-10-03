# Season transition operation, `mconeworld:season_transition` (normative)

- Written by: MCOneWorld Tool T9, `tool.core.transition.SeasonTransition`.
- Decisions: [ADR 0010](../decisions/0010-end-reset-by-archive.md) (End archive), [ADR 0011](../decisions/0011-offline-season-activation.md) (offline activation).
- The plan and journal use the shared operation formats ([`operation-journal-v1.md`](operation-journal-v1.md)). This document fixes how this operation fills them, so that any Tool version can resume, roll back, or audit a recorded transition.

## 1. Plan

| Member | Value |
|---|---|
| `type` | `mconeworld:season_transition` |
| `impact` | `WORLD_MUTATING` |
| `backup` | `ExistingBackup`: the newest recorded full backup whose backup operation completed with `world:files` equal to the world's now. It is re-verified, every byte, before `BACKUP_CONFIRMED`. |
| `rollback` | `INVERSE_STEPS`: before the commit step, the End is restored from this operation's archive. After it, the full backup is restored by hand. |

### 1.1 Targets: the new season

The plan carries the whole new season, so a resumed operation needs nothing but its journal. Targets, in this order:

1. `season`: the new season's id.
2. `extension` `mconeworld:season_name`, key `<n>.<hex>`: the display name's UTF-8 bytes in lowercase hex, split into parts of at most 120 digits, numbered from 0. The parts are concatenated in order.
3. `extension` `mconeworld:season_origin`, key `<dimension>:<x>:<y>:<z>`: the Overworld origin, then the Nether origin. The dimension is everything before the last three `:`-separated fields.
4. `extension` `mconeworld:season_spawn`, key `<dimension>:<x>:<y>:<z>`: the planned spawn (Overworld).
5. `extension` `mconeworld:archived_dimension`, key `minecraft:the_end`: present only when the End is archived.
6. `sidecar_manifest`.
7. `season`: the previously active season, present only if one was active.

The season's `createdAt` is the plan's `createdAt`.

### 1.2 Preconditions

| Key | Kind | Value |
|---|---|---|
| manifest | `AT_REVISION` | revision and SHA-256 of the manifest's exact bytes |
| `world:files` | checksum | fingerprint of every world file except `session.lock`: path, size, and modification time |
| `world:files_outside_end` | checksum | the same, also without `dimensions/minecraft/the_end` |
| `world:end_files` | checksum | the same, of `dimensions/minecraft/the_end` only |
| `world:seed` | checksum | `WorldBinding.seedChecksum` of the seed |
| `sidecar:chunk-index` | checksum | the chunk-index pin |
| `minecraft:data_version` | fact | the world's data version (always 4903 here) |
| `mconeworld:end_records` | fact | the number of End records in the index |

### 1.3 Steps

When the world has End files, four steps; otherwise only `commit_season`.

| # | Action | Effect | Retry |
|---|---|---|---|
| 0 | `mconeworld:archive_end` | Copy every file of `dimensions/minecraft/the_end` into backup `<operation id>` (`DIMENSION_ARCHIVE`, entries `world/dimensions/minecraft/the_end/…`; [`backup-v1.md`](backup-v1.md)), verify the payload, and record it in the manifest. | Already recorded: done. A partial copy of this backup is deleted and copied again. |
| 1 | `mconeworld:remove_end_files` | Check that every End file is listed in the archive with the same size and SHA-256, then delete them, then the emptied directories. | End directory gone: done. Files already deleted are skipped. |
| 2 | `mconeworld:commit_season` | One manifest transaction: `appendSeason` (planned, with both origins and the spawn), `activateForApplication` (the previous season becomes `ARCHIVED`), and, if archived, `withArchivedDimension(minecraft:the_end, previous season, this operation, the archive, now)`. **Commit point.** | Season present: done. |
| 3 | `mconeworld:remove_end_records` | Write every End record to `reports/season-transition-<operation>.jsonl` (§2) and reference it in the manifest, then remove the End's records from the index. | Report referenced: not rewritten. Removal is idempotent. |

A file in the End that the archive does not hold, or one that differs from it, is a terminal `tool.transition.changed`: nothing more is deleted. So is a changed End before archiving. An archive that does not verify is a terminal `tool.transition.archive_unverified`.

### 1.4 Checks

In this order, those for the End only when it is archived:

1. `mconeworld:end_archived`: the End directory is gone, and the recorded archive verifies.
2. `mconeworld:world_outside_end_unchanged`: `world:files_outside_end` is unchanged. This covers `level.dat`, player data, and the other dimensions.
3. `mconeworld:season_active_pending`: the new season is active and pending application, the archived End is recorded, and the archive state validates.
4. `mconeworld:index_matches_world`: the index holds exactly the world's chunks.
5. `mconeworld:index_verifies`: the chunk index verifies.
6. `mconeworld:report_complete`: the report holds exactly `mconeworld:end_records` records.

### 1.5 Rollback

After a terminal failure before the commit step, the Tool rolls back at once (`ROLLBACK_STARTED`, then `ROLLBACK_COMPLETED` or `ROLLBACK_FAILED`). An interrupted or failed rollback is retried only on request.

- **With a recorded archive,** the archive is re-verified. Each archived file that is missing from the world is copied to `<name>.mconeworld-restore-<operation>` beside its target, synced, checked against its SHA-256, and renamed into place.
- **A file that exists** must equal its archived copy. Otherwise the rollback fails with `tool.transition.rollback_conflict` and **overwrites nothing**.
- **Without a recorded archive,** nothing was deleted, so nothing is restored. A partial archive copy may remain; it is never recorded, and it is deleted only on request.
- **Kept after rollback.** The recorded archive stays: a verified copy, not a loss.
- **Not restored.** Empty directories are not recreated; Minecraft creates them.

After the commit, rollback is refused (`tool.operation.rollback_refused`).

## 2. End-records report

The report uses the reconcile report's format, [`mconeworld-reconcile-report` 1.0](reconcile-report-v1.md), unchanged:

- **Header:** `operation` is the transition's id, and `removed` is the number of End records.
- **Manifest reference:** in `files`, with role `archived-records`, dimension `minecraft:the_end`, plus its checksum and size.
- **Records:** in `ChunkOrder`.
