# MCOneWorld shared modules: quick reference

This is a quick reference for people and agents building **other** MCOneWorld components (offline tool, Paper plugin, Fabric client) on top of the shared libraries. The binding rules are in [`AGENTS.md`](AGENTS.md) and the root [`AGENTS.md`](../AGENTS.md). Decisions are in [`docs/decisions/`](../docs/decisions/).

## Modules

| Module | Artifact | Root package | Depends on | Used by |
|---|---|---|---|---|
| [`domain`](domain/README.md) | `mconeworld-shared-domain` | `io.github.kjmorse2.mconeworld.domain` | — | Tool, Paper, Client |
| [`sidecar`](sidecar/README.md) | `mconeworld-shared-sidecar` | `io.github.kjmorse2.mconeworld.sidecar` | `domain` | Tool, Paper (**never** Client) |
| [`protocol`](protocol/README.md) | `mconeworld-shared-protocol` | `io.github.kjmorse2.mconeworld.protocol` | `domain` | Paper, Client |
| [`test-fixtures`](test-fixtures/README.md) | `mconeworld-shared-test-fixtures` | `io.github.kjmorse2.mconeworld.fixtures` | all three | tests only |

Group ID: `io.github.kjmorse2.mconeworld` (ADR 0001).

## Guarantees consumers can rely on

- **Java 21 bytecode** (`--release 21`), built and tested on JDK 25. Consumers on Java 21 and on Java 25 can both load it. Each module's smoke test fails if this changes.
- **No platform types.** Two checks fail the build if any shared artifact references Paper, Bukkit, Fabric, Minecraft, Adventure, JavaFX, or MCA Selector:
  - a resolved-graph check
  - a `jdeps` bytecode check
- **Null-marked APIs.** Every package is JSpecify `@NullMarked`. Parameters and returns are non-null unless annotated `@Nullable`. JSpecify is an `api` dependency.
- **No serialization or database library types in public APIs.** Those stay internal (ADR 0003).
- **Reproducible jars.** Each jar carries `META-INF/LICENSE` (MIT).

## Public API

Each prompt adds its summary here. Javadoc on each type is the authoritative contract.

### `domain`: identifiers (Prompt 02)

| Type | Purpose | Key rules |
|---|---|---|
| `SeasonId`, `OperationId` | Stable UUID identities, independent of display names | `parse(String)` accepts only the canonical lowercase 36-character form. The nil UUID is rejected. There is no random factory: callers supply a UUID. Ordering is by text. |
| `DimensionKey` | Namespaced dimension id (`minecraft:overworld`, `mymod:x/y`) | Minecraft `Identifier` characters, but stricter: the namespace is mandatory, and empty, `.` and `..` segments are rejected. At most 256 characters. Constants `OVERWORLD`, `THE_NETHER`, `THE_END`. |
| `InvalidDomainValueException` | The one documented failure of every factory and constructor | Branch on `code()` (a `DomainErrorCode`), never on the message |
| `DomainErrorCode` | Stable codes such as `domain.coordinate.out_of_range` | The code strings are a contract, pinned by `DomainErrorCodeTest` |

### `domain.coordinate`: coordinates (Prompt 02)

| Type | Purpose |
|---|---|
| `BlockPosition(dim, long x, int y, long z)` | Absolute block, which is authoritative. `.chunk()` |
| `ChunkPosition(dim, int x, int z)` | `containing(block)` = `floorDiv(·, 16)`. `min/maxBlockX/Z`, `.region()`, `regionLocalX/Z` (0..31). Ordered by dimension, X, Z. |
| `RegionPosition(dim, int x, int z)` | `containing(chunk)` = `floorDiv(·, 32)`, which is one `.mca` file. `min/maxChunkX/Z`. |
| `DimensionOrigin(dim, long x, int y, long z)` | A season origin. `toLocal(block)`. Y is a reference height and is not transformed. |
| `SeasonLocalPosition(origin, long x, int y, long z)` | Presentation-only local coordinates. It carries its origin, and `toAbsolute()` never fails. |
| `OverworldNetherScaling` | Accepts only `minecraft:overworld` and `minecraft:the_nether`. Methods: `toNetherExact` (rejects non-multiples of 8), `toNetherFloor` (explicit floor), `toOverworld`, `pairedNetherOrigin`, `isPaired`. |
| `OriginAlignment` | Reports `remainderX/Z` (floorMod) and `isAligned()`. It never rounds. `recommendedFor`: Overworld 128, everything else 16. |
| `WorldBounds` | *World* limits, checked explicitly: `contains`, `containsChunkHorizontally`. `VANILLA_OVERWORLD`/`NETHER`/`END` and `vanilla(minY, height)` are verified against 26.2: horizontal `[-30000000, 29999999]`, Y at most `-2032..2031`. |
| `CoordinateLimits` | *Representation* limits: block X/Z within ±2^35, which is exactly the range whose chunk fits in an `int`, so conversions are total. Region X/Z within ±2^26. |

Every position requires a dimension. X/Z math is `long` with `Math.*Exact`. Nothing is clamped or rounded, except in methods named `…Floor`.

## Building

```bash
./gradlew sharedCheck      # everything CI runs: tests, format, Error Prone/NullAway, architecture guards
./gradlew spotlessApply    # fix formatting (google-java-format + SPDX header)
./gradlew :shared:domain:test --tests '*Smoke*'   # narrow run
```

Requirements:

- A local **JDK 25**. Toolchains are never auto-downloaded.
- Gradle runs on a full JDK, because the bytecode guard uses `jdeps`.

`offline-tool/` is a separate build (`cd offline-tool && ./gradlew build`, on JDK 21).

## Rules that trip people up

- A new or changed dependency must be:
  - added to `gradle/libs.versions.toml`
  - locked (`./gradlew --no-configuration-cache resolveAndLockAll --write-locks`)
  - verified (`--write-verification-metadata sha256`, run from a clean copy of the tree)

  Strict locking and checksum verification reject anything else. See the root README.
- Production code in `sidecar` or `protocol` may never reference the other, or `test-fixtures`.
- `-Xlint:all -Werror` and Error Prone are on. Fix warnings; do not suppress them globally.
- Tests run with `user.timezone=UTC` and an `en_US` locale. Never read the wall clock or the default random source in code under test. Inject a `Clock` or a seeded random instead.
