# MCOneWorld Tool

The offline desktop application of MCOneWorld (JavaFX 25, Java 25). With the server stopped, it opens a world and its sidecar, initializes or reconciles the sidecar, creates verified backups, and creates new seasons: a distant origin and spawn, a fresh End, and offline activation.

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
- for every action, whether it can start, or each reason it can't (server running, sidecar locked, recovery needed, unfinished operation, version not pinned, and so on)

It also checks that the sidecar belongs to this world (manifest 1.1's seed binding), that a newer-format manifest is treated read-only, and that a pending activation blocks the next season.

T4, shared formats. Manifest 1.1 (world binding, pending activation, archived dimensions) and the `mconeworld-backup` 1.0 format (`docs/formats/backup-v1.md`) are in `shared:sidecar`.

T5, initialization (`tool.core.init.SidecarInitializer`, operation `mconeworld:sidecar_init`). T6, reconcile (`tool.core.reconcile.Reconciler`, operation `mconeworld:reconcile`). Both run through the shared journal runner (`tool.core.operation.OperationRunner`). The app still opens an empty main window.

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

**Prerequisites.** The server is stopped (`session.lock` free). The sidecar location doesn't exist or is an empty directory, and is not inside the world. The world's seed is readable.

**Backup.** None is needed: nothing in the world is written.

**If interrupted.** Every step can be retried. The session offers **Resume** (`resume`), which continues from the journal. If the world changed after planning, the operation stops and nothing is guessed. To start over, delete the new sidecar directory; the world is untouched.

**Real worlds.** Validated read-only on both local 26.2 worlds (`./gradlew :tool:core:localWorldTest -PlocalWorlds=TestSaves`): 117,792 chunks in 0.8 s for the long-lived world.

```sh
./gradlew toolCheck          # tests (UI tests run headless through Monocle), formatting, analysis, guards
./gradlew :tool:app:run      # start the Tool
./gradlew :tool:world:localWorldTest -PlocalWorlds=TestSaves   # read-only check against local real worlds
```

**Chunk trimming** (deleting unwanted chunks) is not part of the Tool. Use MCA Selector, kept unmodified in [`offline-tool/`](../offline-tool/), with the server stopped. Then open the world in the Tool and run **Reconcile**.

## Reconciling after trimming (T6)

Run this after trimming the world with MCA Selector (server stopped), or whenever the world changed without the plugin.

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

**Prerequisites.** The server is stopped. The sidecar is usable and belongs to this world. No other operation is unfinished.

**Backup.** None is needed: the world is only read, and removed history is in the report.

**If interrupted.** Every step can be retried; **Resume** continues from the journal and reproduces the same report bytes. If the world changes while planning or applying, the operation stops and nothing is guessed.

