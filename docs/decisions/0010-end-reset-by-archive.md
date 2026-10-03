# ADR 0010: Resetting the End by archiving it

- Status: **Accepted**, 2026-10-02, by the project owner (tool-planning session). It resolves root `AGENTS.md` §22, "whether each season receives a separate End", and amends root §3, which preserved the End until a strategy was approved.
- Implemented by: MCOneWorld Tool, milestone T9 (`mconeworld:season_transition`). See [`tool-implementation-plan.md`](../architecture/tool-implementation-plan.md).

## 1. Context

A new season starts in distant, unused Overworld and Nether terrain. The End can't be handled that way:

- its main island, exit portal, gateways, and dragon fight are at fixed absolute coordinates around `(0, 0)`
- a new season expects a fresh dragon fight

Root §3 forbade *silently* resetting the central island or the dragon-fight data, and deferred the strategy. The owner wants each new season to get a fresh End.

## 2. Decision

When the Tool creates a new season, it **archives the whole End dimension directory**, so Minecraft generates a fresh End, with a fresh dragon fight, on the next start. This is part of the season-transition operation and follows the full safe protocol (root §10).

**Where the dragon fight lives** (owner-provided, 2026-10-02; **confirmed** for the vanilla 26.2 layout on two real worlds in [`world-format-26.2.md`](../compatibility/world-format-26.2.md) §3):

- **Java 26.1 and newer:** in the End dimension's own saved data, `dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat`. Servers that keep each dimension in its own world folder (Paper) have a different prefix, but the file is still under that dimension's `data/minecraft/`.
- **Before 26.1:** in `level.dat`, at `Data/DragonFight` or `Data/DimensionData/1/DragonFight`.

On the pinned 26.2, the fight state therefore sits inside the directory being archived. **No `level.dat` edit is needed**, and the Tool never writes NBT.

**The arena's data is spread across several places,** and archiving the whole dimension directory handles each consistently:

| Data | Where it lives | How it is handled |
|---|---|---|
| Central island, pillars, exit portal, gateways, End cities | End region files (blocks and structure data) | Archived |
| The dragon and End crystals | End entity-region files | Archived |
| Gateway progression, respawn stage, exit-portal location | The dragon-fight saved data | Archived |
| Player End advancements | Each player's advancement file | **Not** in the End directory. The plugin's season progression reset handles them (ADR 0011); the Tool never edits player data. |

Archiving only some of these, for example the dragon-fight record without the chunks or the reverse, would leave an inconsistent arena, so the archive always takes the whole dimension directory.

1. **Preconditions:**
   - The world's data version is the pinned one (26.2, data version 4903); any other version is refused.
   - The server is stopped (`session.lock` is not held) and the sidecar writer lock is held.
   - A verified full backup exists.
2. **Plan and preview:** the files of the End dimension directory with their sizes (region, entities, poi, and `data/`, including the dragon fight), and the season being created.
3. **Apply:**
   1. Copy the End dimension's files into the operation's backup area, `backups/<id>/` (format: ADR 0012), and verify every checksum.
   2. Only then delete the originals. Copying and verifying before deleting is safe even when the world and the sidecar are on different filesystems.
   3. **Paper's separate world folder** (for example `world_the_end/`) also holds folder-level files such as its own `level.dat`. Only the dimension's data directory is archived; the folder-level files stay. The exact Paper 26.2 layout is verified in T2 before any code depends on it.
4. **Index:** the End's chunk-provenance records are written to a report under `reports/`, then removed from the index. The terrain they describe no longer exists in the world.
5. **Commit:** the manifest records the archived dimension (format addition, manifest 1.1) in the same transaction that activates the new season (ADR 0011).
6. **Rollback** restores the End dimension directory from the operation's own verified copy.

**Verification before implementation (root §18).**

- **Dragon-fight location:** confirmed for vanilla 26.2 (`world-format-26.2.md`).
- **Paper dimension-folder layout:** still unverified. Neither sample world has been saved by Paper 26.x. Until a world saved by Paper 26.2 is inspected, the End archive runs only on the verified vanilla layout (`dimensions/minecraft/the_end/`), and any other layout is inspect-only.

If a version keeps the fight in `level.dat` instead, that version stays inspect-only until a new revision of this ADR adds a lossless `level.dat` writer.

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

- Manifest 1.1 adds the root `archivedDimensions` records (`manifest-v1.md` §3.1). It is a minor addition; a 1.1 reader reads 1.0 directly, so no migration exists.
- No world format changes: the result is an ordinary vanilla world whose End has not been generated yet.

## 6. How it is tested

- Synthetic worlds (no Mojang assets):
  - interruption at every journal step, proving the old state, the new state, or a completed rollback
  - wrong data version refused
  - held `session.lock` refused
  - checksum mismatch in the copy refused before any deletion
  - `level.dat` and every file outside the End dimension directory byte-identical before and after
- Manual validation, recorded, on a disposable copy of a real 26.2 world: after the transition, Paper 26.2 generates a fresh End with a new dragon fight. This also confirms the dragon-fight file location and the Paper layout.
