# shared:domain

Artifact: `io.github.kjmorse2.mconeworld:mconeworld-shared-domain` · Package: `io.github.kjmorse2.mconeworld.domain`

**Responsibility.** Platform-neutral, validated domain values shared by at least two products:

- identifiers
- dimensions
- block, chunk, region, and season-local coordinates
- seasons and chunk provenance
- operation state
- structured validation results

**May depend on:** JDK and JSpecify annotations only.

**Forbidden:**

- other shared modules
- serialization, compression, or database libraries
- Paper, Bukkit, Fabric, Minecraft, Adventure, JavaFX, MCA Selector (`net.querz`), and mappings

`verifySharedDependencies` and `verifySharedBytecode` enforce these rules.

**Status:** identifiers and coordinates are done (Prompt 02). Seasons, provenance, and validation come in Prompt 03, and operations in Prompt 07.

Packages:

- `domain`: `SeasonId`, `OperationId`, `DimensionKey`, `DomainErrorCode`, `InvalidDomainValueException`
- `domain.coordinate`: `BlockPosition`, `ChunkPosition`, `RegionPosition`, `DimensionOrigin`, `SeasonLocalPosition`, `OverworldNetherScaling`, `OriginAlignment`, `WorldBounds`, `CoordinateLimits`

See [`../README.md`](../README.md#public-api) for the API summary.
