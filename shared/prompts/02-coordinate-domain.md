# Prompt 02 — Identifiers and Coordinate Domain

Implement the foundational platform-neutral domain values in `shared:domain`. Read the root and shared agent instructions first.

## Objective

Provide immutable, validated types for identifiers and coordinate mathematics that every later module can trust.

## Required types and behavior

Implement approved equivalents of:

- `SeasonId`
- `OperationId`
- `DimensionKey`
- `BlockPosition`
- `ChunkPosition`
- `RegionPosition`
- `DimensionOrigin`
- Season-local X/Z representation or transform result
- Origin-alignment validation result

Required behavior:

1. Validate namespaced dimension identifiers without depending on a Minecraft class.
2. Use `long` for absolute/local X/Z calculations and checked arithmetic.
3. Use mathematical floor division for block-to-chunk and chunk-to-region conversions.
4. Provide explicit checked Overworld/Nether scaling helpers; do not generalize scaling to arbitrary dimensions.
5. Detect non-exact or misaligned conversions instead of silently rounding when exact alignment is required.
6. Require a dimension for every persistent position.
7. Keep serialization annotations out of core domain types unless the approved architecture explicitly permits them.
8. Document world-bound validation separately from representation bounds so tests can model future/custom limits safely.

## Test-first expectations

Write boundary tables before or alongside implementation. Cover at minimum:

- `0`, `1`, `15`, `16`, `17`
- `-1`, `-15`, `-16`, `-17`
- region transitions around chunks `31`, `32`, `33`, `-31`, `-32`, `-33`
- origin-local round trips
- 128-block Overworld alignment and corresponding Nether chunk alignment
- overflow and underflow near `long` limits
- malformed namespaced identifiers
- equality and immutability guarantees

Add property-based tests for reversible transforms within a bounded valid range and for agreement with `Math.floorDiv`/`Math.floorMod`.

## Constraints

- Do not add season lifecycle, JSON, filesystem, or protocol behavior.
- Do not clamp to Minecraft’s current border implicitly.
- Do not use doubles for persistent integer coordinate transforms.
- Do not expose unchecked primitive conversion methods.

## Acceptance criteria

- All coordinate invariants are documented in Javadoc and tests.
- Tests demonstrate correct negative-coordinate behavior.
- No platform-specific dependency enters `shared:domain`.
- Formatting, static analysis, all shared tests, and existing affected builds pass.

End with the public API summary and exact test results. Stop after this milestone.

