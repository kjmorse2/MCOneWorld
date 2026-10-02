# ADR 0010: Resetting the End by archiving it

- Status: **Accepted**, 2026-10-02, by the project owner (tool-planning session). It resolves root `AGENTS.md` §22, "whether each season receives a separate End", and amends root §3, which preserved the End until a strategy was approved.
- Implemented by: MCOneWorld Tool, milestone T9 (`mconeworld:season_transition`). See [`tool-implementation-plan.md`](../architecture/tool-implementation-plan.md).

## 1. Context

A new season starts in distant, unused Overworld and Nether terrain. The End can't be handled that way:

- its main island, exit portal, gateways, and dragon fight are at fixed absolute coordinates around `(0, 0)`
- a new season expects a fresh dragon fight

Root §3 forbade *silently* resetting the central island or the dragon-fight data, and deferred the strategy. The owner wants each new season to get a fresh End.

## 2. Decision

When the Tool creates a new season, it **archives the whole End dimension and resets the dragon fight**, so Minecraft generates a fresh End on the next start. This is part of the season-transition operation and follows the full safe protocol (root §10).

1. **Preconditions:**
   - The world's data version is the pinned one (26.2, data version 4903); any other version is refused.
   - The server is stopped (`session.lock` is not held) and the sidecar writer lock is held.
   - A verified full backup exists.
2. **Plan and preview:** the End's region, entities, and poi files with their sizes; the dragon-fight fields to reset; the season being created.
3. **Apply:**
   1. Copy the End dimension's files into the operation's backup area, `backups/<id>/` (format: ADR 0012), and verify every checksum.
   2. Only then delete the originals. Copying and verifying before deleting is safe even when the world and the sidecar are on different filesystems.
   3. Reset the dragon-fight data in a lossless NBT round trip that keeps every unknown tag. Write a temp file in the same directory, fsync it, and rename it atomically.
4. **Index:** the End's chunk-provenance records are written to a report under `reports/`, then removed from the index. The terrain they describe no longer exists in the world.
5. **Commit:** the manifest records the archived dimension (format addition, manifest 1.1) in the same transaction that activates the new season (ADR 0011).
6. **Rollback** restores the End files and the dragon-fight data from the operation's own copies.

**The exact location of the dragon-fight data in 26.2** (a `level.dat` tag or a separate data file) must be verified against the pinned server before implementation, through its generated reports or a recorded manual inspection. It is never assumed (root §18). Every step above applies to whichever location is verified.

**Players.** Players whose saved position is in the End are moved and reset by the plugin when it applies the activation (ADR 0011). The Tool does not edit player data.

## 3. Alternatives

| Alternative | Why not |
|---|---|
| Delete only the central island and its gateway ring, keeping outer builds | Needs chunk-level deletion across the region, entities, and poi files, which is the riskiest world edit. The outer islands players explore would be the old ones. |
| A separate End dimension per season, through a datapack | Keeps old Ends playable, but it is mostly plugin and datapack work and changes portal behaviour. It can be added later; the archives this ADR keeps would let old Ends be restored into such a dimension. |
| Keep the End untouched (the previous rule) | The owner wants a fresh End each season. |

## 4. Consequences

- **Old End builds can't be visited in game** after a season transition. They are kept, checksummed, in the sidecar's backup area and listed in the manifest, so they can be restored or reopened as an archive dimension by a later feature.
- **This is the Tool's only world mutation in v1.** It runs only on the pinned version, and other versions are inspect-only.
- **The Paper plugin must tolerate a regenerated End** (a new dragon fight, a new exit portal) and must not "repair" the absence of the old one.

## 5. Migration impact

- Manifest 1.1 adds archived-dimension records (shared format work, milestone T4) together with a 1.0 → 1.1 migration.
- No world format changes: the result is an ordinary vanilla world whose End has not been generated yet.

## 6. How it is tested

- Synthetic worlds (no Mojang assets):
  - interruption at every journal step, proving the old state, the new state, or a completed rollback
  - wrong data version refused
  - held `session.lock` refused
  - checksum mismatch in the copy refused before any deletion
  - NBT round trip preserving unknown tags
  - a semantic NBT diff showing that only the reset fields changed
- Manual validation, recorded, on a disposable copy of a real 26.2 world: after the transition, Paper 26.2 generates a fresh End with a new dragon fight.
