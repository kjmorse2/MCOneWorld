# shared:sidecar

Artifact: `io.github.kjmorse2.mconeworld:mconeworld-shared-sidecar` · Package: `io.github.kjmorse2.mconeworld.sidecar`

**Responsibility.** Everything under the `mconeworld-data/` directory next to a world save:

- the deterministic JSON manifest codec (ADR 0003)
- explicit schema migrations
- storage safety: atomic writes, checksums, locking, path confinement
- the `ChunkIndex` abstraction

It operates on a caller-supplied root and never rewrites anything on a read-only open.

**May depend on:** `shared:domain`, and internal-only libraries accepted by ADR. These must never appear in the public API.

**Forbidden:** `shared:protocol`, `shared:test-fixtures` in production code, and every platform dependency.

**Status:** manifest v1 is implemented (Prompt 04). Storage safety comes in Prompt 05, migrations in Prompt 06, and chunk indexes in Prompts 08 and 09.

Packages:

- `sidecar.manifest` (API): `ManifestCodec`, `ManifestDocument`, `DecodedManifest`, `ManifestException`, `ManifestProblemKind`, `ManifestIssueCodes`, `ManifestLimits`, `SchemaVersion`, `FileReference`, `FeatureKey`, `FeatureRecord`, `PreservedMembers`, `ManifestFiles`. The format is [`docs/formats/manifest-v1.md`](../../docs/formats/manifest-v1.md).
- `sidecar.storage` (API): `SidecarPath`, `UnsafeSidecarPathException`.
- `sidecar.internal.json` (**internal, not API**): the strict JSON reader over jackson-core, and the canonical writer.

jackson-core is an `implementation` dependency. Its types never appear in public signatures. Platform jars that shade it must relocate `tools.jackson.core`.
