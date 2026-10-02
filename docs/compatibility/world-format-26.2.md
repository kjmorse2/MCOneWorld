# Minecraft 26.2 world format: observed facts (Tool T2)

- Observed: 2026-10-02, read-only, on two owner-provided Java 26.2 worlds (data version 4903). The worlds live under `TestSaves/`, which is git-ignored; no world content is committed.
  - A **light** world: a fresh singleplayer world with little progress.
  - A **long-lived** world: a copy of the production world, about 118,000 Overworld, Nether, and End chunks. `realms-upload.log` shows it began as a dedicated-server world in the pre-26.1 layout (`world/playerdata`, `world/poi/…`), was uploaded to Realms, and was later downloaded as 26.2.
- Purpose: the ground truth that MCOneWorld Tool's world reader (`tool/world`, T2) and the End archive (ADR 0010, T9) are built against. Root §18 forbids guessing these.

## 1. Layout (26.1 and newer, vanilla)

```text
<world>/
  level.dat, level.dat_old, session.lock (3 bytes), icon.png
  data/minecraft/*.dat          world-level saved data, including world_gen_settings.dat (the seed)
  datapacks/
  players/{data,advancements,stats}/<uuid>.*
  dimensions/minecraft/<overworld|the_nether|the_end>/
    region/  entities/  poi/    r.<x>.<z>.mca
    data/minecraft/             per-dimension saved data:
                                  world_border.dat, raids.dat, chunk_tickets.dat,
                                  the End only: ender_dragon_fight.dat
```

- **Every vanilla dimension has its own directory**, which also holds that dimension's `data/`. Player data is under `players/`, no longer `playerdata/`.
- **Not Paper's layout.** The long-lived world reports `ServerBrands = [fabric, vanilla]`, so neither copy has been opened by Paper 26.x. Paper's 26.2 dimension layout (it historically used separate `world_nether/` and `world_the_end/` folders) is therefore **still unverified**. The Tool treats any non-vanilla layout as inspect-only until a world saved by Paper 26.2 has been inspected (ADR 0010).
- **Third-party files in a dimension's `data/`** are possible. The long-lived world has a `DistantHorizons.sqlite` (a client-mod cache) in each dimension. They belong to that dimension, and an End archive takes them along.

## 2. `level.dat` (gzip-compressed NBT, root compound `Data`)

| Field | Observed |
|---|---|
| `Data.DataVersion` | `4903` |
| `Data.Version` | `{Id: 4903, Name: "26.2", Series: "main", Snapshot: 0}` |
| `Data.LevelName` | the world's name |
| `Data.spawn` | `{pos: int[3], dimension: "minecraft:overworld", yaw, pitch}`. This replaces the older `SpawnX`/`SpawnY`/`SpawnZ`. |
| `Data.ServerBrands` | the list of server brands that have opened the world |
| Seed | **not** in `level.dat`. It is in `data/minecraft/world_gen_settings.dat` at `data.seed` (a long), next to `data.dimensions`. Before 26.1 it was at `Data.WorldGenSettings.seed`. |

## 3. The dragon fight (ADR 0010)

- **Confirmed** at `dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat`, inside the End dimension directory.
- Keys are snake_case under `data`: `dragon_killed`, `previously_killed`, `needs_state_scanning`, `respawn_time`, `gateways` (the remaining gateway order), `exit_portal_location`, and `dragon_uuid`.
- The file carried `DataVersion` 4786. It was written by an earlier 26.x version and not rewritten since.

Archiving the whole End dimension directory therefore takes the fight state along. `level.dat` holds no dragon-fight data in 26.2.

## 4. Region files

| | Light world | Long-lived world |
|---|---|---|
| Region chunks (Overworld / Nether / End) | 4,242 / 0 / 0 | 98,626 / 11,284 / 7,882 |
| Entity chunks | 204 | 15,659 |
| POI chunks | 36 | 3,411 |
| `.mca` files of **0 bytes** | 8 | 171 |
| Compression types | 2 (zlib) only | 2 (zlib) only |
| `.mcc` external chunks | none | none |

- **Zero-byte region files are normal.** Minecraft leaves them behind. They mean "no chunks", not corruption.
- **Header layout** (unchanged since Anvil): 1,024 big-endian entries of a 3-byte sector offset and a 1-byte sector count, then 1,024 big-endian timestamps (epoch seconds). Chunk data starts at sector 2.
- **Header-only reading.** Region-chunk presence and its last-save timestamp come from the header alone, without decompressing any chunk. This is what the Tool's inventory uses.
