# Prompt 03 — Season, Provenance, and Validation Domain

Extend `shared:domain` with season semantics while preserving the coordinate contracts established in Prompt 02.

## Objective

Define a model that can represent current and historical seasons without falsely claiming exclusive chunk ownership or inferring state from ordering.

## Required concepts

Implement approved equivalents of:

- `SeasonRecord`
- `SeasonStatus`
- `ArchiveManifest` domain view or manifest-independent archive state
- `ChunkEnvelope`
- `ChunkProvenance`
- Provenance evidence source and confidence
- `ProtectedRegion`
- `PortalMarker`
- `BackupRecord`
- `PlayerArchiveRecord` metadata only; do not design item serialization yet
- `ValidationIssue`, severity, stable issue code, and validation result collection

## Semantic requirements

1. Active season selection is explicit and validated.
2. Season display names are bounded but are not identifiers and need not be unique.
3. A season can omit an origin for any dimension.
4. Spawn positions are absolute and dimension-qualified.
5. A chunk can have first-observed season, later activity, legacy/unknown provenance, and independent protected state.
6. A chunk envelope is a derived summary and cannot be substituted for membership.
7. Portal markers store absolute destinations and stable IDs; they do not grant authorization by themselves.
8. Timestamps use injected or passed `Instant` values, never implicit wall-clock reads in domain constructors.
9. Validation produces stable machine-readable codes plus human-readable context.

## Tests

- Valid and invalid lifecycle transitions.
- Missing, duplicated, and nonexistent active-season references.
- Rename behavior preserving identity.
- Dimension-origin absence.
- Empty and extreme envelopes.
- Legacy provenance and multi-season activity.
- Protected state independent from provenance.
- Stable validation codes and deterministic issue ordering.
- Immutability of maps, lists, and nested values.

Use builders only in test fixtures or clearly named construction APIs; avoid constructors with long primitive parameter lists.

## Constraints

- No JSON annotations, file I/O, database code, Paper APIs, or network encoding.
- Do not decide how millions of chunk records are physically stored.
- Do not imply that a stored portal marker is safe to teleport to.
- Do not add End-reset semantics; represent dimensions generically.

## Acceptance criteria

All semantic invariants have focused tests, previous shared tests remain green, and public collections cannot be mutated through aliases. Report API additions, test evidence, and any schema questions deferred to Prompt 04.

