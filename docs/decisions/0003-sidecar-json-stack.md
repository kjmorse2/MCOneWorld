# ADR 0003 — Sidecar JSON library and canonical encoding

- Status: **Accepted** (Q4)
- Approval: accepted 2026-09-28 by the implementing agent under explicit owner delegation. It is implemented in Prompt 04.
- Date: 2026-09-27
- Milestone: Prompt 00. The implementation is Prompt 04 (manifest v1). The library is added to the build only when Prompt 04 needs it.

## Context

Requirements from root `AGENTS.md` §9 and `shared/AGENTS.md`:

- Human-reviewable metadata uses deterministic, versioned JSON. Serialization order must be deterministic so that checksums are repeatable.
- Unknown fields from a newer *compatible* schema must survive read-modify-write.
- Parsing of untrusted input must be bounded: nesting depth, string length, document size, and collection sizes.
- Read-only opening must never rewrite files.
- **No serialization library may appear in a public domain API.**

This is a *persistent-format-adjacent* decision. The library itself is replaceable, but the canonical byte form it produces defines the checksums. The canonical form is therefore specified independently of the library below, so the library can be swapped without changing any file.

## Candidates evaluated

| Criterion | Jackson 3.2.3 (`tools.jackson`) | Gson 2.14.0 | Hand-written parser/writer |
|---|---|---|---|
| License | Apache-2.0 | Apache-2.0 | n/a |
| Maintenance | Active: 3.2.3 released 2026-09-22 | Active: 2.14.0 | Maintained by us |
| Streaming and tree APIs | Both (`JsonParser`, `JsonNode`) | Both (`JsonReader`, `JsonElement`) | Only what we write |
| Built-in input limits | `StreamReadConstraints`: max nesting depth, string length, number length, name length, and document length | `JsonReader.setNestingLimit` only. Size limits need a bounded `Reader` wrapper. | We write them |
| Duplicate-key rejection | `StreamReadFeature.STRICT_DUPLICATE_DETECTION` | Tree parsing silently keeps the last value. Detection needs a custom `TypeAdapter`. | We write it |
| Deterministic output | Sorted map keys, and a custom writer for full control | Insertion-ordered `JsonObject`. Sorting is manual. | Exact |
| Unknown-field preservation | Retain an `ObjectNode` of unrecognized members per record | Retain a `JsonObject` of unrecognized members | We write it |
| Platform classpath risk | Not shipped by Paper or Minecraft. Must be shaded and relocated in the Paper jar and the Fabric mod. | **Minecraft and Paper already ship Gson**, at a version we don't control, unless relocated | None |
| Size | jackson-core 0.61 MB; with databind and annotations ≈ 2.7 MB | 0.31 MB | 0 |
| Already used in repo | No | Yes (MCA Selector: `gson:2.12.1`) | No |

## Proposed decision

1. **Library:** Jackson 3 **`jackson-core` streaming API only** (`tools.jackson.core:jackson-core:3.2.3`), used inside `<ROOT>.sidecar` implementation packages.
   - No `jackson-databind`.
   - No annotations on domain types.
   - Mapping between JSON and domain records is hand-written in `sidecar` codecs.

   This gives us:
   - built-in hardened input limits and duplicate-key detection
   - a small dependency (jackson-core ≈ 600 KB)
   - no reflection
   - domain types that stay library-free

   Databind's convenience does not justify reflection-driven binding over untrusted input. The Paper plugin and Fabric mod relocate the package when shading.

2. **Canonical byte form (independent of the library):**
   - UTF-8 without BOM, with LF line endings and exactly one trailing LF.
   - Two-space indentation, and `": "` after names.
   - Object members sorted by code-point order of their names. Arrays keep their semantic order, and each schema defines whether an array is ordered or a set; sets are sorted by a documented key.
   - **No floating-point numbers in any schema.** Integers only, restricted to the signed 64-bit range. Decimal values are strings with a documented grammar. This avoids number-canonicalization ambiguity.
   - Timestamps are strings in UTC ISO-8601 with a `Z` suffix and a fixed precision, which the manifest schema chooses in Prompt 04.
   - Strings use minimal escaping: `"`, `\`, and control characters. Non-ASCII characters are emitted literally.
   - `null` means "intentionally unset". An absent member means "absent". Unknown values use an explicit representation per field (root §7). The schema defines which states are legal per field.
   - Checksums are taken over the exact canonical bytes and written as algorithm-qualified strings (`sha256:<hex>`).

3. **Unknown-field preservation:**
   - Each decoded record keeps an immutable, opaque bag of unrecognized members as canonical JSON bytes or tokens. Only the `sidecar` codec can read it.
   - Re-encoding merges the bag back in, sorted.
   - Unknown members under a **newer major** version fail closed and are never preserved blindly.

4. **Duplicate keys are rejected** as corruption, not resolved by last-wins.

## Implementation amendments (Prompt 04, 2026-09-28)

1. **The writer is in-house (`CanonicalJsonWriter`), not `JsonGenerator`.** Jackson's pretty-printer defaults (separators, indentation) are library behaviour that could change between versions. An upgrade must never change canonical bytes, and therefore checksums. The writer is about 100 lines, and golden fixtures pin its output. jackson-core remains the parser.
2. **Readers accept any conformant whitespace, including CRLF.** Earlier drafts proposed rejecting CRLF, but hand edits on Windows are legitimate. A reader reports `canonical=false`, and never rewrites. A BOM is still rejected.
3. **Duplicate keys, unpaired surrogates, and element-count limits are checked by our own tree builder.** They carry precise codes and locations, independent of Jackson's messages. Jackson's own `StreamReadConstraints` are configured one unit looser, as a backstop. Every `JsonReadFeature.ALLOW_*` leniency is disabled explicitly, including any added in later versions.
4. **UTF-8 is validated up front with the JDK's strict decoder.** It rejects overlong forms and encoded surrogates before parsing.
5. **Null policy:** optional values are always present as JSON `null`; an absent member is an error. This gives one textual form per value.
6. **Timestamps** use the shortest exact ISO-8601 UTC form (`ISO_INSTANT`), with years 0000–9999. Readers require the `Z` suffix.

Normative details are in [`docs/formats/manifest-v1.md`](../formats/manifest-v1.md).

## Alternatives

- **Gson.** Acceptable fallback, and the offline tool already depends on it. Rejected as the primary choice for three reasons: weaker built-in input limits, silent duplicate-key handling in tree mode, and version conflicts with the Gson that Paper and Minecraft bundle.
- **Jackson databind.** Rejected. Its reflection- and annotation-driven binding would pull library types toward domain classes and widen the attack surface.
- **Hand-written parser.** Rejected. A security-sensitive parser is a maintenance burden, and a maintained, hardened streaming parser does the same job. We still hand-write the *writer* path through `JsonGenerator` with explicit ordering, which gives the same determinism.
- **RFC 8785 (JCS).** Considered for canonicalization. JCS mandates ECMAScript number formatting and compact output, but the root AGENTS asks for human-reviewable, diff-friendly files. The rules above are a pretty-printed subset with no floats. JCS could still be used later for a detached-signature form.

## Consequences

- The Paper plugin and Fabric client must relocate `tools.jackson.core`. The Fabric client needs it only if it ever reads JSON, and per root §14 it never reads the sidecar.
- Codecs are more code than annotations would be, but they are explicit, reviewable, and testable per field.
- Golden fixtures pin the canonical bytes, so a library upgrade that changes output fails the tests.

## Migration impact

None yet. This is the first sidecar format. Once manifest v1 ships, the canonical-form rules are part of the format version, and any change requires a migration (`shared/AGENTS.md`, "Format evolution").

## How this is tested (Prompt 04 and Prompt 05)

- Golden-byte fixtures:
  - encode → bytes equal the fixture
  - decode → encode → identical bytes
- jqwik round-trip properties.
- Rejection tests, each asserting a stable error code:
  - duplicate keys
  - depth, string, and document limits exceeded
  - floats present
  - BOM present
  - CRLF line endings
- Unknown-field preservation across read-modify-write.
- Differential test: re-parse the output with a second parser (Gson, test scope only) and compare the trees.
