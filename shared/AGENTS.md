# MCOneWorld Shared Modules — Agent Instructions

This file supplements the repository-root `AGENTS.md` and the MCOneWorld shared family prefix. Read both completely before changing anything under `shared/`. If they conflict, stop and request a decision unless this file contains an explicit, documented module-specific override.

## Mission

The shared project defines the stable, platform-neutral contracts used by MCOneWorld Tool, MCOneWorld Paper, and MCOneWorld Client. It owns domain semantics, sidecar formats, coordinate math, operation state models, and the optional client/server protocol. It does not own Minecraft world mutation, Paper behavior, Fabric rendering, or desktop UI.

The shared project is successful when all three products can depend on the same contracts without importing one another or depending on Minecraft implementation classes.

## Module layout

Use these Gradle subprojects unless an accepted architecture decision changes them:

```text
shared/
├── AGENTS.md
├── domain/
├── sidecar/
├── protocol/
└── test-fixtures/
```

Dependency direction:

```text
domain       <- sidecar
domain       <- protocol
domain       <- test-fixtures
sidecar      <- test-fixtures (test scope only where practical)
protocol     <- test-fixtures (test scope only where practical)
```

Rules:

- `domain` has no dependency on the other shared modules.
- `sidecar` may depend on `domain`, but not on `protocol`.
- `protocol` may depend on `domain`, but not on `sidecar`.
- Production modules must not depend on `test-fixtures`.
- No shared module may depend on Paper, Bukkit, Fabric, Minecraft, JavaFX, MCA Selector, Adventure, or an NMS/mappings artifact.
- Do not create a generic `common` dumping ground. A type belongs in `domain` only if its semantics are shared by at least two user-facing products.

## Build boundaries

- Use the repository’s pinned Gradle wrapper, Java toolchain, dependency locking, formatting, and static-analysis configuration.
- Do not introduce dynamic dependency versions or repositories without an architecture decision.
- Keep all shared modules compatible with the Java toolchain selected by the repository compatibility matrix.
- Prefer Java records and immutable collections at public boundaries.
- Do not expose a serialization library, compression library, database driver, or platform-specific type in a public domain API.
- Configure reproducible archives and deterministic tests.
- Generated schema documentation belongs in the build output unless the repository explicitly tracks it.

## Package responsibilities

The exact Java package prefix is chosen during repository bootstrap and then treated as stable. Within it, keep responsibilities explicit:

```text
...domain                 IDs, positions, seasons, validation primitives
...domain.coordinate      Checked coordinate transforms and conversions
...domain.operation       Plans, operation states, journal events
...sidecar.manifest       Manifest representation and codecs
...sidecar.migration      Explicit schema migrations
...sidecar.storage        Atomic files, checksums, locking, path safety
...sidecar.index          Chunk-index abstraction and selected implementation
...protocol               Versions, capabilities, message types
...protocol.codec         Bounded deterministic wire codecs
...fixtures               Test-only builders and canonical fixtures
```

Avoid packages named `util`, `misc`, `helpers`, or `common` unless their contents have one narrowly documented responsibility.

## Domain model requirements

Implement domain types as validated values rather than passing unrelated primitives through APIs. The shared family prefix lists the minimum concepts; the shared project is responsible for making their invariants executable.

### Identity

- `SeasonId`, `OperationId`, backup IDs, and archive-record IDs are stable identifiers independent of display names.
- Parse IDs through checked factories that return structured validation failures or documented exceptions.
- Display names are user-editable, need not be unique, and must be length-bounded at every trust boundary.
- Player identity uses the existing Minecraft account UUID. Shared code may represent it as `UUID`, but must never generate replacement player identities as part of a reset.

### Dimensions

- Represent a dimension with a validated namespaced identifier, not a vanilla-only enum.
- Accept identifiers such as `minecraft:overworld` and future custom dimensions when syntactically valid.
- Do not place Paper `NamespacedKey`, Fabric `Identifier`, or Minecraft `ResourceLocation` in shared APIs. Platform modules perform those conversions.

### Coordinates

- Absolute coordinates are canonical. Season-local coordinates are derived views.
- X/Z calculations use `long` internally and must detect overflow.
- Y is an `int` unless a documented future format requires otherwise.
- Block-to-chunk and chunk-to-region conversions use mathematical floor division for negative values.
- Do not silently clamp, wrap, truncate, or round a coordinate.
- A coordinate without a dimension is not a valid persistent location.
- The shared API must distinguish block, chunk, region, and local coordinate types so they cannot be accidentally interchanged.
- Overworld/Nether scaling is an explicit checked operation. It is not a generic property of arbitrary dimensions.
- Origin alignment validation reports violations; it does not silently change the requested origin.

### Seasons

- A season has a stable ID, display name, creation time, lifecycle status, dimension origins, and an absolute spawn.
- An active season is selected by the manifest, not inferred from newest timestamp or list order.
- Historical records are append-oriented. Renaming a season does not change its identity.
- The model must support a dimension having no local origin.
- The model must not assume the End shares the Overworld origin.

### Chunk provenance

- Do not model chunks as exclusively owned by one season.
- Preserve separate concepts for first observation/generation, later activity, protection, evidence source, and confidence.
- Legacy chunks discovered before MCOneWorld tracking use an explicit unknown/legacy state.
- Bounding rectangles are derived summaries, not authoritative membership sets.
- Index APIs must be streaming or paged; callers must not need to load every known chunk into memory.

### Validation

Provide structured validation results containing at least:

- Stable issue code
- Severity
- Human-readable message
- Logical field or record location
- Optional remediation hint
- Optional causal exception for diagnostics, excluded from portable serialization

Do not make callers parse log messages to determine what failed.

## Sidecar responsibilities

The sidecar is a directory adjacent to, but outside, Minecraft world directories. Shared code operates on a caller-supplied sidecar root and must not discover server paths through global state.

### Required properties

- A versioned deterministic JSON manifest stores low-volume metadata.
- Large per-chunk data uses an implementation behind `ChunkIndex`; the encoding is selected by an architecture decision, not incidentally.
- Manifest paths are normalized relative paths. Reject absolute paths, traversal, ambiguous normalization, and escapes through symlinks where applicable.
- Every referenced data file has an algorithm-qualified checksum.
- Writes use staged temporary output and atomic replacement when supported.
- A journal makes interrupted multi-file operations detectable.
- Readers validate before exposing records as usable state.
- Read-only opening must never rewrite, migrate, normalize, or “repair” files.
- Unknown newer major schema versions fail closed.
- Compatible unknown fields must survive supported read-modify-write workflows rather than being silently erased.
- Migrations are explicit transformations from one declared version to the next and produce a report.
- Sidecar locking prevents concurrent writers. Locking behavior and stale-lock recovery require tests and documentation.

### Format evolution

Maintain distinct versions for:

- Manifest schema
- Chunk-index encoding
- Player-archive encoding, when introduced
- Network protocol

Do not use the application release number as a substitute for format versions.

Every persistent-format change requires:

1. Updated schema or format documentation.
2. Forward/backward compatibility analysis.
3. Golden fixtures for old and new forms.
4. Migration or an explicit incompatibility error.
5. Tests proving failure leaves the source unchanged.

## Operation plan and journal

Shared code defines state and validation; platform modules perform world-specific actions.

An operation plan must include:

- Stable operation ID and operation type
- Creation time and tool/plugin version
- Input world and sidecar identities without non-portable absolute paths in the persistent plan
- Preconditions and expected checksums/revisions
- Exact logical targets
- Backup requirement and referenced backup record
- Ordered steps
- Validation steps
- Declared rollback strategy
- Plan checksum or equivalent tamper/change detection

Use a constrained state machine such as:

```text
PLANNED -> BACKED_UP -> APPLYING -> VALIDATING -> COMPLETED
                         |              |
                         v              v
                       FAILED <---------+
                         |
                         v
                    ROLLED_BACK
```

Illegal transitions must fail. Replaying an already completed step must be safe or be rejected deterministically. Journals are append-oriented; do not rewrite history to pretend a failed attempt never occurred.

## Protocol responsibilities

The protocol module defines platform-neutral messages exchanged between MCOneWorld Paper and the optional Fabric client.

Initial logical messages:

- `mconeworld:hello`
- `mconeworld:context`

The protocol must support:

- Explicit protocol version negotiation
- Client capability advertisement
- Server-authoritative active season and revision
- Dimension-specific origins
- Graceful rejection or downgrade for incompatible optional features
- Bounded payloads and collections
- Deterministic byte encoding
- Golden byte fixtures

Do not serialize sidecar records directly onto the wire. Define minimal wire DTOs so persistent and network schemas can evolve independently.

The protocol module must not open sockets, register Paper channels, register Fabric receivers, or depend on a specific packet buffer. Provide codecs over standard Java byte/data abstractions or another platform-neutral boundary selected by an architecture decision.

## Test-fixture policy

`shared:test-fixtures` is test support, not production code.

- Fixtures must be small, deterministic, legally redistributable, and documented.
- Builders should make invalid cases explicit rather than silently producing valid defaults.
- Golden files include format version and expected checksum metadata.
- Canonical fixtures are immutable. Tests copy them before mutation.
- Corrupt fixtures should target one documented failure at a time where possible.
- Do not commit full user worlds, player-identifying data, proprietary server JARs, or secrets.
- Avoid snapshots so broad that harmless formatting changes obscure semantic regressions.

## Testing requirements

Every behavior change requires tests at the lowest meaningful level. Do not postpone core correctness tests to the Paper or Tool projects.

### Required domain coverage

- Coordinate transforms at zero, positive, negative, boundary, and overflow-adjacent values.
- Floor-division behavior around `-1`, `-15`, `-16`, `-17`, region boundaries, and configured world limits.
- Dimension-identifier parsing and rejection.
- Origin alignment and exact Nether scaling.
- Season lifecycle and active-season invariants.
- Validation issue stability.
- Equality, ordering, and deterministic representations where public behavior relies on them.

### Required sidecar coverage

- Deterministic serialization.
- Valid manifest round trips.
- Unknown compatible-field preservation.
- Unsupported-version rejection.
- Missing file, checksum mismatch, traversal, symlink escape, malformed JSON, oversized input, and interrupted-write cases.
- Atomic replacement and recovery behavior on supported filesystems.
- Lock contention and stale-lock policy.
- Migration success, migration rejection, and source immutability on failure.
- Large-index streaming behavior and bounded-memory tests.

### Required operation coverage

- Every legal and illegal state transition.
- Idempotent replay or deterministic duplicate rejection.
- Changed-precondition detection.
- Journal truncation/corruption handling.
- Recovery recommendations derived from the last durable state.

### Required protocol coverage

- Round trips for every message.
- Golden bytes for every supported protocol version.
- Minimum/maximum numeric and length bounds.
- Unknown optional capability handling.
- Malformed, truncated, oversized, duplicate, and incompatible payloads.
- Proof that wire DTOs do not expose mutable internal collections.

### Test quality

- Use parameterized tests for boundary tables.
- Use property-based tests for coordinate math, codec round trips, and index set algebra when a maintained library is approved.
- Use temporary directories for filesystem tests.
- Do not depend on test execution order, network access, wall-clock time, locale, or system timezone.
- Inject clocks, random sources, and filesystem abstractions where determinism requires it.
- A test that covers a bug must fail for the unfixed implementation when practical.
- Measure coverage as a diagnostic, not as a substitute for meaningful assertions.

## Performance and resource limits

Long-lived worlds may contain millions of chunks.

- Do not expose APIs that require materializing all chunk records.
- Establish benchmark datasets and explicit memory/time budgets before selecting the chunk-index encoding.
- Prefer iterators, streams with documented ownership, cursors, or paged queries.
- Ensure streams and file handles are closed deterministically.
- Parsing limits must be configurable within safe hard maxima.
- Benchmarks belong in a separate source set and do not replace correctness tests.
- Optimize only after profiling representative workloads; preserve the simple reference implementation for differential testing where useful.

## API compatibility

- Treat exported shared APIs as contracts between independently evolving platform modules.
- Minimize exported packages and types.
- Avoid returning internal mutable representations.
- Additive source compatibility does not automatically imply wire or persistent-format compatibility.
- Breaking API changes require updating all in-repository consumers atomically and recording the decision.
- Do not preserve a flawed persistent format solely to avoid an internal API change; instead provide an explicit migration.

## Error handling and observability

- Separate user-correctable validation failures from I/O failures, corruption, programmer errors, and unsupported versions.
- Never hide corruption behind defaults.
- Include operation, season, and file identifiers in diagnostic context without exposing unnecessary player data.
- Shared modules do not choose logging backends. Accept an abstraction or return structured diagnostics where necessary.
- Exception messages are for humans; stable error codes are for automation.

## Agent workflow in `shared/`

Before implementation:

1. Read the root instructions and this file.
2. Inspect Gradle settings, existing ADRs, schemas, and tests.
3. Identify the prompt milestone being implemented.
4. Confirm earlier milestone acceptance criteria still pass.
5. List any persistent-format, protocol, or public-API effects.

During implementation:

- Keep the change within one milestone.
- Prefer contract tests before platform integrations exist.
- Add fixtures only through documented fixture builders or reviewable files.
- Do not choose a deferred technology merely to unblock unrelated work.
- If a requirement would couple shared code to Minecraft internals, stop and move the adapter responsibility to the platform module.

Before completion:

1. Run formatting, static analysis, and all affected tests.
2. Run the complete shared-module test suite.
3. Verify deterministic output by repeating relevant serialization/build tests.
4. Review public API and dependency changes.
5. Update format documentation, examples, and ADRs.
6. Report verified commands, failures, risks, and intentionally deferred work.

## Shared-project definition of done

A shared milestone is complete only when:

- Public contracts and invariants are documented.
- Relevant unit, property, golden, filesystem, corruption, and migration tests pass.
- Output is deterministic and bounded.
- No platform-specific dependencies entered shared modules.
- Persistent or wire formats are explicitly versioned.
- Failure does not mutate source data unless the operation contract explicitly says otherwise.
- All current in-repository consumers compile, or temporary consumers defined by the milestone prove the API usable.
- The next milestone does not need to guess what this milestone guarantees.

