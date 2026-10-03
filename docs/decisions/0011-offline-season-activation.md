# ADR 0011: Offline season activation with pending application

- Status: **Accepted**, 2026-10-02, by the project owner (tool-planning session). It refines root `AGENTS.md` §13, which made Paper responsible for "season activation and world-spawn application".
- Implemented by: shared manifest 1.1 (milestone T4) and the Tool's season transition (T9). See [`tool-implementation-plan.md`](../architecture/tool-implementation-plan.md).

## 1. Context

Creating a new season changes several things at once:

- the sidecar's active season
- the world spawn
- every player's progression: an archive, then a reset
- the End (ADR 0010)

Spawn placement needs generated terrain to find a safe Y, and player resets need the server's item serialization (root §11), so both have to run in the live server. The owner wants the whole transition prepared offline in the Tool while the server is stopped.

## 2. Decision

The **Tool activates the season** in the sidecar, offline, and marks that activation **pending application**. The **plugin applies it** at its next start.

1. **In one manifest transaction, the Tool:**
   - appends the new season with its origins and nominal spawn
   - archives the previously active season
   - activates the new one
   - records the new season as the root `pendingActivation` (manifest 1.1)
2. **When the plugin finds an active season with `pendingActivation` set, it:**
   - generates the spawn area
   - finds a safe Y at the planned spawn X/Z
   - applies the world spawn
   - archives and resets players under its own journaled operation
3. **The plugin then clears the marker** in a sidecar commit. Until it is cleared, the plugin keeps players out of normal play, or applies the reset on each player's first join, as the plugin design decides.
4. **The Tool never sets a world spawn and never edits player data.**

A season with the marker set is fully valid sidecar state. It is not a recovery case. Applying it is idempotent: the plugin journals each player's reset, so an interrupted application resumes per player.

## 3. Alternatives

| Alternative | Why not |
|---|---|
| The plugin activates; the Tool only plans (status `PLANNED`) | The owner's choice is to prepare the whole season offline in one tool and one confirmation. |
| The Tool also writes the spawn into `level.dat` | The Y isn't known in ungenerated terrain, and root §13 keeps spawn application in the runtime. |

## 4. Consequences

- **Plugin contract** (recorded for the Paper project): on startup, apply any pending activation before normal play. Report clearly if application is impossible (for example, an unsupported version). Never clear the marker without completing every step.
- **Offline tool:** a season can't be activated while one is still pending application. The Tool refuses a second transition until the plugin has applied the first.
- **Vanilla export** (root §3): removing MCOneWorld leaves the world with its old spawn and an un-applied season, which is a valid vanilla world. The export documentation says so.

## 5. Migration impact

Manifest 1.1 adds the root member `pendingActivation` (a season id or `null`; `manifest-v1.md` §3.1). It is a minor addition, so there is no migration: a 1.1 reader reads 1.0 directly, with nothing pending.

Two rules keep the marker safe for readers that do not understand it:

- **Stale markers are harmless.** A marker naming a season other than the active one is reported as a warning (`archive.pending_activation.stale`) and ignored.
- **Actors read newer minors read-only** (`manifest-v1.md` §5.1). A component that does not understand the marker never writes over it, so it never activates another season or runs as though the pending one were applied.

The marker is root-level rather than per season, because at most one activation can be pending, and that is checkable by construction.

## 6. How it is tested

- **Shared:** `ArchiveStateV11Test` (pending until marked applied, every other transition refused meanwhile, stale marker a warning, unknown season an error) and `ManifestV11Test` (1.0 still reads; the member is required in 1.1 and unknown in 1.0), plus the golden `full-1.1.json` and the manifest fuzzer.
- **Tool:** the transition sets the marker exactly in its commit transaction, and a second transition is refused while the marker is set.
- **Plugin:** tests are owned by the Paper project.
