# Prompt 04 — Sidecar Manifest Version 1

Implement the first deterministic, human-reviewable manifest format in `shared:sidecar` using the approved JSON stack. Do not implement large chunk indexes yet.

## Objective

Create a versioned manifest codec that maps persistent JSON to validated domain state without coupling domain types to the JSON library.

## Manifest v1 must represent

- Manifest schema version
- Stable archive/world identity
- Monotonic sidecar revision
- Active season ID
- Ordered or deterministically serialized season records
- Dimension origins and absolute season spawn
- References to chunk indexes and other sidecar files by safe relative path
- Algorithm-qualified checksums for referenced files
- Portal markers
- Backup records
- Feature/configuration metadata that genuinely belongs to archive history
- Extension/unknown fields according to the approved preservation policy

## Requirements

1. Publish a normative format document and JSON examples under `docs/formats/`.
2. Keep JSON DTOs internal to `shared:sidecar`; map explicitly to/from domain values.
3. Produce deterministic encoding: stable field and collection ordering, newline policy, character encoding, and numeric representation.
4. Bound document size, string lengths, collection counts, and nesting depth.
5. Reject duplicate keys if the selected parser can enforce this; otherwise document and test the chosen unambiguous policy.
6. Distinguish syntax errors, schema errors, unsupported versions, referential errors, and domain-validation errors.
7. Read-only decode must never rewrite input.
8. Preserve compatible unknown fields through the supported read-modify-write path.
9. Do not serialize machine-specific absolute paths.
10. Do not make the application release version the manifest schema version.

## Tests

- Golden canonical manifest bytes and checksum.
- Decode/encode/decode semantic round trip.
- Deterministic output across repeated runs and randomized insertion order.
- Unknown compatible fields at documented extension points.
- Unsupported major/minor versions according to the approved policy.
- Duplicate IDs, invalid active-season references, malformed dimensions, unsafe paths, checksum syntax, excessive sizes, duplicate keys, truncation, and malformed Unicode.
- Locale/timezone independence.
- Proof that a failed decode leaves files untouched.

Use temporary directories only where a filesystem is necessary. Most codec tests should operate on bounded streams or byte arrays.

## Constraints

- Do not add automatic migrations yet.
- Do not add atomic writes or lock management yet.
- Do not select the chunk-index encoding.
- Do not store raw player item data in the manifest.

## Acceptance criteria

The format document, golden fixtures, public codec boundary, validation behavior, and all tests agree exactly. Existing tests remain green. End with a compatibility statement describing precisely what v1 readers accept and reject.

