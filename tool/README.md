# MCOneWorld Tool

The offline desktop application of MCOneWorld (JavaFX 25, Java 25). It works on a world folder it is given, normally downloaded from the server and uploaded back afterwards together with its sidecar. It opens a world and its sidecar, initializes or reconciles the sidecar, creates verified backups, and creates new seasons: a distant origin and spawn, a fresh End, and offline activation.

- **Plan and status:** [`docs/architecture/tool-implementation-plan.md`](../docs/architecture/tool-implementation-plan.md)
- **Rules for agents:** [`AGENTS.md`](AGENTS.md)
- **Decisions:**
  - [ADR 0009](../docs/decisions/0009-tool-and-mca-selector.md): MCA Selector relationship
  - [ADR 0010](../docs/decisions/0010-end-reset-by-archive.md): End reset
  - [ADR 0011](../docs/decisions/0011-offline-season-activation.md): offline activation
  - [ADR 0012](../docs/decisions/0012-backup-format.md): backups

**Status:** T2, read-only world access. `tool/world` reads `level.dat`, the dimension layout, region headers, and `session.lock`, and decides whether a world is writable (only 26.2 on the verified layout). It was validated against two real 26.2 worlds (`docs/compatibility/world-format-26.2.md`).

T3, session status. `tool/core`'s `ToolSession.inspect(locations, clock)` opens a world together with its sidecar (by default `mconeworld-data` beside the world), read-only. It reports:

- the world inspection
- the sidecar state: absent, empty, incomplete, present, needs migration, or unreadable
- recovery leftovers, the chunk index, and the operation journals
- for every action, whether it can start, or each reason it can't (world open in Minecraft, sidecar locked, recovery needed, unfinished operation, version not pinned, and so on)

It also checks that the sidecar belongs to this world (manifest 1.1's seed binding), that a newer-format manifest is treated read-only, and that a pending activation blocks the next season.

T4, shared formats. Manifest 1.1 (world binding, pending activation, archived dimensions) and the `mconeworld-backup` 1.0 format (`docs/formats/backup-v1.md`) are in `shared:sidecar`.

T5, initialization (`tool.core.init.SidecarInitializer`, operation `mconeworld:sidecar_init`). T6, reconcile (`tool.core.reconcile.Reconciler`, operation `mconeworld:reconcile`). T7, backups (`tool.core.backup.BackupService`, operation `mconeworld:backup`). T8, season planner (`tool.core.season.SeasonPlanner`). T9, season transition (`tool.core.transition.SeasonTransition`, operation `mconeworld:season_transition`). The operations run through the shared journal runner (`tool.core.operation.OperationRunner`), which also rolls back a failed operation that can still be undone. The app still opens an empty main window.

## Initializing a sidecar (T5)

What it does: creates the sidecar for an existing world. The world is only read.

1. **Preview** (`preview`). Scans every region, entity, and POI header and shows the chunk counts per dimension and any damaged headers. It writes nothing.
2. **Plan.** The plan pins three things: the world's seed hash, a digest of every region header, and that no manifest exists yet.
3. **Apply** (`apply`). Creates the sidecar directory if needed, records the plan in an operation journal, and then:
   1. creates the first manifest, bound to the world's seed
   2. creates an empty chunk index
   3. records every existing chunk as *legacy* provenance: its season is unknown and never guessed
4. **Validate.** Three checks:
   - the index holds exactly the world's chunks, and the world is unchanged
   - the index verifies
   - the manifest is bound to this world

**Prerequisites.** The world isn't open in Minecraft (`session.lock` free). The sidecar location doesn't exist or is an empty directory, and is not inside the world. The world's seed is readable.

**Backup.** None is needed: nothing in the world is written.

**If interrupted.** Every step can be retried. The session offers **Resume** (`resume`), which continues from the journal. If the world changed after planning, the operation stops and nothing is guessed. To start over, delete the new sidecar directory; the world is untouched.

**Real worlds.** Validated read-only on both local 26.2 worlds (`./gradlew :tool:core:localWorldTest -PlocalWorlds=TestSaves`): 117,792 chunks in 0.8 s for the long-lived world.

```sh
./gradlew toolCheck          # tests (UI tests run headless through Monocle), formatting, analysis, guards
./gradlew :tool:app:run      # start the Tool
./gradlew :tool:world:localWorldTest -PlocalWorlds=TestSaves   # read-only check against local real worlds
```

**Chunk trimming** (deleting unwanted chunks) is not part of the Tool. Use MCA Selector, kept unmodified in [`offline-tool/`](../offline-tool/), on the same world folder. Then open the world in the Tool and run **Reconcile**.

## Reconciling after trimming (T6)

Run this after trimming the world with MCA Selector, or whenever the world changed without the plugin.

1. **Preview** (`preview(locations, acknowledgeProtectedRemovals)`). Compares every region chunk of the world with the chunk index, and shows per dimension:
   - chunks gained
   - records of chunks lost, grouped by the season that first saw them
   - which of the lost chunks were protected

   It writes nothing.
2. **Protected chunks.** If a protected chunk is gone (explicitly protected, or inside a protected region), the preview refuses and lists them, until the user acknowledges their removal.
3. **Apply.** In order:
   1. Write every record about to be removed, in full, to `reports/reconcile-<operation>.jsonl` (`docs/formats/reconcile-report-v1.md`). If the sidecar had no world binding, bind it now.
   2. Record gained chunks: as first seen in the active season, with low confidence because the evidence is an offline scan; or as legacy if no season is active. Then remove the lost chunks' records.
4. **Validate.** Three checks:
   - the index now matches the world exactly
   - the index verifies
   - the report holds every planned removal
5. **Afterwards.** A second preview reports *nothing to do*, and apply refuses.

**Prerequisites.** The world isn't open in Minecraft. The sidecar is usable and belongs to this world. No other operation is unfinished.

**Backup.** None is needed: the world is only read, and removed history is in the report.

**If interrupted.** Every step can be retried; **Resume** continues from the journal and reproduces the same report bytes. If the world changes while planning or applying, the operation stops and nothing is guessed.

## Backups (T7)

1. **Preview** (`preview`). Lists and sizes everything to copy:
   - every regular file of the world, except `session.lock`
   - every file of the sidecar, except `backups/`, the lock files, the staging area, and the running backup's own journal

   It refuses a symbolic link, a file name a backup cannot hold, or too little free space on the sidecar's disk (the copy plus 5 % plus 64 MiB). It writes nothing.
2. **Apply** (`apply`). Copies everything into `backups/<id>/` (`docs/formats/backup-v1.md`), hashing each file on the way, then:
   1. checks that the world's paths, sizes, and modification times did not change during the copy
   2. re-reads every copied byte against the hashes
   3. records the backup in the manifest, the commit point

   Only a verified backup is ever recorded.
3. **Validate.** The recorded backup verifies against its `backup.json`, and the world did not change.
4. **Verify later** (`BackupService.verify`). Re-reads a recorded backup at any time and reports partial, changed, missing, and unexpected files.

**Prerequisites.** The world isn't open in Minecraft, the sidecar is usable, and there is enough free space.

**If interrupted or cancelled** (interrupt the worker thread). **Resume** removes this backup's own partial copy and copies again; a partial copy is never recorded.

**Restore.** Restore is manual for now: `RestoreInstructions.forFullBackup` gives the exact steps. They move the current world and sidecar aside (never delete them), copy the backup's `world/` and `sidecar/` back, and keep every backup.

**Real worlds.** On the long-lived world, 2,288 files (1.1 GB) were copied and verified in 3.3 s on a RAM disk (`localWorldTest`). Expect disk speed on a real disk.

## Planning a season's origin (T8)

`SeasonPlanner` is pure logic, and the season transition (T9) uses it.

**Rules.** An Overworld origin is valid when:

- **Aligned.** It is a multiple of 128 blocks, so its Nether counterpart (÷8) is chunk-aligned. A misaligned pick is reported with the nearest aligned alternatives; it is never rounded.
- **Inside the border.** The square of `clearanceBlocks` around it (10,000 by default, configurable) fits inside the vanilla world border.
- **Clear.** That square holds no generated Overworld chunk, its ÷8 counterpart holds no generated Nether chunk, and it overlaps no other non-cancelled season's square.

**Proposal.** `propose` places the origin just beyond the bounding box of everything that exists, on the axis direction nearest to (0, 0). The result is valid by construction, and validated anyway.

**Spawn.** The spawn is planned at the origin's X/Z at a nominal Y. The server finds a safe height when it applies the season (ADR 0011).

**Real world.** On the long-lived world the proposal is Overworld (27,008, 0) and Nether (3,376, 0).


## Creating a season (T9)

This is the only operation that writes to the world. The exact contract is [`docs/formats/season-transition-v1.md`](../docs/formats/season-transition-v1.md).

**Prerequisites.** All must hold:

- The world isn't open in Minecraft (`session.lock` free).
- The world is 26.2 (data version 4903) in the vanilla 26.1+ layout.
- The sidecar is usable and belongs to this world, with no unfinished operation.
- No earlier season is still waiting for the server to apply it.
- The index matches the world: reconcile first.
- A full backup was made of the world exactly as it is now. Any change to a world file after the backup requires a new one.

**The steps:**

1. **Preview** (`preview(locations, request)`). The request names the season and gives its placement, usually `SeasonPlanner.propose`. The preview checks every prerequisite and the placement rules: aligned and clear, a paired Nether origin, and the spawn inside the clear area. It then shows:
   - the new season and the season it archives
   - the full backup it relies on
   - the End's files, bytes, and index records
   - how many of those records are protected; archiving them must be acknowledged

   It writes nothing.
2. **Apply** (`apply`). First re-verifies every byte of the full backup, then:
   1. copies the End directory (`dimensions/minecraft/the_end`, which holds the dragon fight in 26.1+) into a verified archive in `backups/<operation>/`, and records it. Paper's per-world configuration and data there (`paper-world.yml`, `data/paper/`) are not End state: they stay in place.
   2. deletes each End file only if it is byte-identical to its archived copy; any other file stops the operation
   3. **commits**: appends the season with its origins and planned spawn, activates it pending application by the server (the previous season becomes archived), and records the archived End
   4. writes the End's index records to `reports/season-transition-<operation>.jsonl`, then removes them from the index
3. **Validate.** Checks:
   - the End is gone and its archive verifies
   - every file outside the End, including `level.dat` and player data, is unchanged
   - the season is active and pending
   - the index matches the world and verifies
   - the report is complete
4. **Next.** Upload the world and its sidecar, and start the server. Minecraft creates the missing End when it loads the world, exactly as for a brand-new world, so the dragon fight starts fresh. The MCOneWorld plugin places the spawn and resets players (ADR 0011). Until the plugin has applied the season, the Tool refuses another one.

**If interrupted.** **Resume** (`resume`) continues from the journal and ends exactly as an uninterrupted run would.

**If it fails before the commit.** The End is restored from the archive at once. A restore never overwrites a file that differs from the archive: it stops and says which file. After that is fixed, **Roll back** (`rollBack`) retries it, and the same call also retries an interrupted restore. The archive stays recorded.

**After the commit.** Nothing is undone automatically. The full backup restores the world and sidecar by hand (`RestoreInstructions`).

**Real worlds.** On temporary copies of both local worlds (`localWorldTest`), the long-lived world's transition archived 408 End files (40 MB) and 7,882 records in about 1 s.

**Why an empty End is enough.** Evidence is in [`world-format-26.2.md`](../docs/compatibility/world-format-26.2.md) §3. Minecraft saves the End's data even for a world whose End was never visited. That never-visited fight file is the fresh state: no dragon killed, and the arena not yet scanned. So a world without End files gets a new End and a new fight on load.
