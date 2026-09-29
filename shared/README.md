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

`ChunkRectangle(dim, minX, minZ, maxX, maxZ)` is inclusive, non-empty geometry: `contains`, `intersects`, `including`, `union`, and `width`/`heightChunks` as `long`.

### More `domain` value types (Prompt 03)

| Type | Rules |
|---|---|
| `BackupId`, `PortalMarkerId`, `ProtectedRegionId`, `PlayerArchiveId` | Same canonical-UUID rules as `SeasonId` |
| `PlayerId` | The player's **existing** account UUID. There is no generating factory, because identities are never fabricated. |
| `DisplayName` | Up to 64 code points. No leading or trailing whitespace, controls, bidi overrides, or unpaired surrogates. **Not an identifier; need not be unique.** |
| `Checksum` | `sha256:<64 lowercase hex>`. `parse`, `ofDigest`, `Algorithm.SHA_256.jcaName()`. |

### `domain.validation` (Prompt 03)

- `ValidationIssue`:
  - stable `IssueCode` in dotted lowercase, e.g. `archive.active_season.not_found`
  - `Severity`: `ERROR`, `WARNING` or `INFO`
  - `IssueLocation`, a JSONPath-like location such as `$.seasons[<id>].origins[minecraft:the_end]`, built with `root().field().key().index()`
  - a message and an optional remediation
  - an optional `cause`, which is diagnostic-only: excluded from equality, never serialized
- `ValidationIssue.fromException(location, InvalidDomainValueException)` reuses the exception's `DomainErrorCode` text as the issue code.
- `ValidationResult`:
  - issues are sorted deterministically (severity, location, code, message)
  - `isValid()` is true when there are no errors; warnings allowed
  - `requireValid()` throws `ValidationException` carrying the result
  - also `withSeverity`, `has`, `plus`

### `domain.season` (Prompt 03)

- `SeasonStatus` lifecycle:
  - `PLANNED → ACTIVE → ARCHIVED`
  - `PLANNED → CANCELLED`
  - `ARCHIVED` and `CANCELLED` are terminal
  - check with `canTransitionTo`
- `SeasonRecord(id, name, createdAt, status, SortedMap origins, spawn)`:
  - origins are an unmodifiable copy keyed by their own dimension, and any dimension may be absent
  - clearly named construction: `SeasonRecord.planned(...)`, `withOrigin`, `withoutOrigin`, `renamed` (keeps the id), `transitionTo`

### `domain.provenance` (Prompt 03): evidence, not ownership

- `ChunkProvenance(chunk, firstObservation, activity, protection)`:
  - `firstObservation` is sealed: `FirstObservation.InSeason(season, at, evidence)` or `FirstObservation.Legacy(at, evidence)`, where the season is unknown and never guessed
  - `activity` holds at most one `SeasonActivity` per season, sorted by season id; `withActivity` keeps the later observation
  - `protection` is a `ChunkProtection` (`NONE`/`PROTECTED`), independent of provenance
- `ProvenanceEvidence(EvidenceSource, Confidence)`. Destructive tooling must not act on `LOW` or `MEDIUM` confidence alone.
- `ChunkEnvelope` is sealed (`Empty` or `Bounded`). It is a **derived summary with no `contains`**; `boundsMayInclude` does not mean membership. `ChunkEnvelope.of(dim, iterator)` streams in constant memory.
- `ProtectedRegion(id, name, ChunkRectangle area, createdAt)`: the area is authoritative, checked with `protects(chunk)`.

### `domain.archive` (Prompt 03)

- `ArchiveState(seasons, @Nullable activeSeasonId, protectedRegions, portalMarkers, backups, playerArchives)`:
  - the manifest-independent aggregate, with unmodifiable lists in append order
  - the constructor enforces structure only
  - `validate()` reports every cross-record problem, with codes in `ArchiveIssueCodes`:
    - active-season missing, not found, not active, or multiple
    - duplicate ids
    - unknown season references
    - origin misaligned or Nether unpaired (warnings)
  - transitions refuse invalid input states and yield valid states: `appendSeason`, `activate` (archives the previous active season), `archiveActiveSeason`, `cancelSeason`, `renameSeason`, `with*`
  - the active season is **only** `activeSeasonId`, never inferred
- `PortalMarker(id, label, recordedDestination, @Nullable destinationSeason, createdAt)`: data, not permission. The destination is unverified, and callers must validate safety and authorization at travel time.
- `BackupRecord(id, createdAt, checksum, sizeBytes ≥ 0, @Nullable operation, @Nullable activeSeason)`.
- `PlayerArchiveRecord(id, PlayerId, season, archivedAt, non-empty categories, physicalContainers)`: metadata only; item serialization is not designed yet.

### `sidecar.manifest`: `manifest.json` v1 (Prompt 04)

The normative spec is [`docs/formats/manifest-v1.md`](../docs/formats/manifest-v1.md).

```java
ManifestCodec codec = new ManifestCodec();                 // or new ManifestCodec(ManifestLimits)
DecodedManifest d = codec.decode(bytes);                   // throws ManifestException (all issues, kinds())
ManifestDocument doc = d.document();                       // d.warnings(), d.canonical()
byte[] out = codec.encode(doc.withState(newState).nextRevision()); // refuses invalid documents
DecodedManifest f = ManifestFiles.readOnly(path, codec);   // never modifies the file
```

- `ManifestDocument(schemaVersion, archiveId, createdAt, revision, ArchiveState state, backupPaths, files, features, preserved)`:
  - builders: `create(archiveId, createdAt)`, `withState`, `withBackup(record, path)`, `withFile`, `withFeature`, `nextRevision`
  - `validate()` runs `ArchiveState.validate()` plus the manifest rules
- Errors are classified by `ManifestProblemKind`, in precedence order: `SYNTAX`, `LIMIT`, `UNSUPPORTED_VERSION`, `SCHEMA`, `REFERENTIAL`, `DOMAIN`.
- Compatibility:
  - major ≠ 1 fails closed
  - 1.0 files are strict: unknown members are rejected
  - 1.N files (N > 0) are accepted with a warning, and unknown members are preserved through read-modify-write
  - namespaced `extensions` objects are always preserved
- `FileReference(role, @Nullable dimension, SidecarPath path, Checksum, sizeBytes)`, `FeatureKey`, `FeatureRecord(adoptedAt, configurationVersion)`, `SchemaVersion`.
- `sidecar.storage.SidecarPath`: the only path form ever stored. It is relative, lowercase, portable, and traversal-proof; failures raise `UnsafeSidecarPathException` (`sidecar.path.unsafe`).
- `ArchiveId` (domain) is the stable archive identity.

### `sidecar.storage`: safe reads, atomic commits, locking, recovery (Prompt 05)

The spec is [`docs/formats/sidecar-storage-v1.md`](../docs/formats/sidecar-storage-v1.md), and the policy is ADR 0004.

```java
SidecarStore store = SidecarStore.open(SidecarRoot.open(path));       // explicit root only
DecodedManifest m = store.readManifest();                              // read-only, writes nothing
RecoveryReport report = store.inspect();                               // read-only leftover classification
try (SidecarWriter w = store.openWriter(owner, clock, StaleLockPolicy.REFUSE)) { // exclusive lock
  w.recover();                                                         // explicit, idempotent
  SidecarTransaction tx = w.begin(m.document().revision());            // BASE_CHANGED if the manifest moved
  StagedFile f = tx.stage(SidecarPath.of("indexes/ow-r7.idx"), in, maxBytes); // new, immutable path
  CommitResult r = tx.commit(nextDoc);                                 // revision must be base + 1
}
```

- **Commit point:** a single atomic rename of `manifest.json`. The old manifest becomes `manifest.previous.json`.
- **Before the commit point:** failure means rollback; the previous manifest is untouched.
- **After the commit point:** only reported, through `cleanupComplete` and `directorySyncSupported`.
- **Committed data files are immutable:** a different file at the same path gives `DATA_FILE_CONFLICT`.
- **No atomic rename** means the write is refused (`ATOMIC_MOVE_UNSUPPORTED`); there is no unsafe fallback.
- **Lock:** a permanent `writer.lock` holding the OS lock, plus `writer.owner.json` metadata with no secrets. Staleness is *provable* only (OS lock free and owner file present), and the default policy is to refuse.
- **POSIX:** never open `writer.lock` yourself inside a process that holds it; closing any descriptor drops `fcntl` locks.
- Errors are `StorageException.code()`, from `StorageIssueCodes` (`sidecar.storage.*`, `sidecar.recovery.*`).
- **Supported:** Linux local filesystems. Not NFS or SMB. Windows is untested.

### `sidecar.migration`: explicit schema migrations (Prompt 06)

The policy is in ADR 0005.

```java
MigrationPlanner planner = new MigrationPlanner(MigrationRegistry.builtIn(), ManifestLimits.DEFAULT);
MigrationPlan plan = planner.plan(store, ManifestCodec.CURRENT);          // pure dry run; writes nothing
VerifiedBackup backup = VerifiedBackup.verify(backupId, copyOutsideSidecar, max); // copy of the source manifest
try (SidecarWriter w = store.openWriter(owner, clock, StaleLockPolicy.REFUSE)) {
  MigrationResult r = new MigrationRunner(registry, limits).apply(w, plan, backup); // explicit; commits atomically
}
```

- **Steps:** each is a pure `bytes → bytes` step moving exactly one version. Paths must be unique and contiguous, and downgrades happen only through explicit downgrade steps.
- **Applying** re-checks the backup, the source checksum, and determinism. It then commits through storage with a persisted report at `reports/migrations/<runId>.json`.
- **Never implicit:** a legacy manifest makes reads fail with `manifest.version.unsupported`, and ordinary writes refuse.
- **Loss is refused** (`UNSUPPORTED_DATA`), never hidden in warnings.
- `MigrationRegistry.builtIn()` is **empty**: 1.0 is the first released schema. v0.9 is a synthetic, test-only fixture.

Test support in `shared:test-fixtures`:

- `ManifestFixtures.fullDocument()`: every v1 member
- `ManifestFixtures.GOLDEN_FULL_V1`: the golden bytes plus a `.meta` file with the format version and SHA-256

`DomainFixtures` provides deterministic ids (`seasonId(n)`, …), `instant(seconds)`, `overworld(x, y, z)`, and `season(n)…build()`.

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
  - locked and verified by running `./scripts/refresh-dependency-state.sh`

  Strict locking and checksum verification reject anything else. See the root README.
- Production code in `sidecar` or `protocol` may never reference the other, or `test-fixtures`.
- `-Xlint:all -Werror` and Error Prone are on. Fix warnings; do not suppress them globally.
- Tests run with `user.timezone=UTC` and an `en_US` locale. Never read the wall clock or the default random source in code under test. Inject a `Clock` or a seeded random instead.
