# Sidecar manifest, schema 1.0 (normative)

- File: `mconeworld-data/manifest.json`
- Implementation: `shared:sidecar`, package `io.github.kjmorse2.mconeworld.sidecar.manifest` (`ManifestCodec`)
- Decisions: ADR 0003 (JSON stack and canonical form)
- Example: [`examples/manifest-v1-example.json`](examples/manifest-v1-example.json). It is byte-identical to the golden fixture `shared/test-fixtures/src/main/resources/io/github/kjmorse2/mconeworld/fixtures/manifest/v1/full.json` (SHA-256 `f0df84d6387018b9d62e9bb2cd5d78fef3aad200025ea73c9de42d452b4aa1a8`), and a test enforces that.

The key words MUST, MUST NOT, and MAY are used as in RFC 2119.

## 1. Purpose and scope

The manifest is the human-reviewable root of an MCOneWorld sidecar. It stores:

- archive identity and a monotonic revision
- all seasons, and the explicit active-season reference
- protected regions, portal markers, backup records and their locations, and player-archive metadata
- references to larger data files (chunk indexes, reports) by safe relative path, checksum, and size
- archive-history feature records
- extension members

It does **not** store per-chunk data (see chunk indexes, Prompts 08–09), raw player item data, or any absolute or machine-specific path.

## 2. Encoding profile

A manifest MUST be a single JSON value (RFC 8259) that also satisfies all of the following. Readers reject any violation. The error code is in parentheses.

| Rule | Code |
|---|---|
| UTF-8, strictly valid: no overlong forms, no encoded surrogates, no truncated sequences | `sidecar.json.invalid_utf8` |
| No byte-order mark | `sidecar.json.bom` |
| Well-formed JSON with exactly one top-level value; no comments, trailing commas, single quotes, unquoted names, leading zeros, or NaN/Infinity | `sidecar.json.malformed` |
| No duplicate member names in any object. They are rejected, never resolved. | `sidecar.json.duplicate_key` |
| Strings and names contain no unpaired UTF-16 surrogates, including via `\u` escapes | `sidecar.json.unpaired_surrogate` |
| Numbers are integers: no fraction, no exponent | `sidecar.json.non_integer_number` |
| Integers lie within the signed 64-bit range | `sidecar.json.number_out_of_range` |

### 2.1 Resource limits

These are defaults (`ManifestLimits.DEFAULT`). Callers may lower or raise them, but never above the hard maxima.

| Limit | Default | Hard maximum | Code |
|---|---|---|---|
| Document size | 16 MiB | 256 MiB | `sidecar.json.limit.document_size` |
| Nesting depth | 32 | 64 | `sidecar.json.limit.nesting_depth` |
| String or member-name length (UTF-16 chars) | 8192 | 1 Mi | `sidecar.json.limit.string_length` |
| Elements per array | 100 000 | 10 M | `sidecar.json.limit.array_elements` |
| Members per object | 10 000 | 1 M | `sidecar.json.limit.object_members` |

A stream is read with a bound (limit + 1 bytes), so an oversized or endless input is rejected without being consumed.

### 2.2 Canonical encoding (what writers produce)

Writers MUST produce exactly this form. Readers accept any profile-conformant formatting and report whether the input was canonical (`DecodedManifest.canonical`).

- UTF-8 without a BOM. LF line endings. Exactly one trailing LF.
- Two-space indentation. Each object member and array element is on its own line. Members are written `"name": value` (one space after the colon). Empty containers are `{}` and `[]`.
- Object members sorted by the **Unicode code point** order of their names.
- Strings escape only `"`, `\`, and U+0000–U+001F. Those controls use `\b \t \n \f \r`, or otherwise `\u00xx` with lowercase hex. Every other character, including non-ASCII, is literal UTF-8.
- Integers in minimal decimal form (`-0` is written `0`).
- Array order is semantic and is specified per member below.

The canonical form is produced by an in-house writer (`CanonicalJsonWriter`), not by a JSON library, so a library upgrade cannot change the bytes, or therefore the checksums.

### 2.3 Scalar types

| Type | JSON | Rule |
|---|---|---|
| uuid | string | Canonical lowercase 36-character hyphenated form. The nil UUID is rejected. |
| timestamp | string | UTC ISO-8601: `YYYY-MM-DDTHH:MM:SS[.f{1,9}]Z`. The `Z` is uppercase. No offsets. Years 0000–9999. The date must exist. Writers use the shortest exact form (Java `DateTimeFormatter.ISO_INSTANT`). |
| dimension | string | Namespaced id per `DimensionKey`, e.g. `minecraft:overworld`. The namespace is mandatory. |
| name | string | `DisplayName`: 1–64 code points; no leading or trailing whitespace; no controls, bidi overrides, or unpaired surrogates. **Not unique.** |
| checksum | string | `sha256:` followed by 64 lowercase hex characters |
| path | string | `SidecarPath`: relative, `/`-separated, segments of `[a-z0-9._-]` (lowercase only), no `.`/`..`/empty segments, no trailing `.` on a segment, no Windows device names, at most 16 segments and 255 characters (`sidecar.path.unsafe`) |
| key | string | Namespaced key with the same grammar as dimension, e.g. `mconeworld:global_strongholds` |
| position | object | `{"dimension", "x", "y", "z"}`. `x`/`z` are integers within ±2^35; `y` is a 32-bit integer. |

**Optional values are always present.** A member that may be empty is written as JSON `null`. It is never omitted. An absent member is `manifest.schema.missing_member`.

## 3. Structure

The root object has these members. Every member is required, except `extensions`.

| Member | Type | Meaning |
|---|---|---|
| `format` | string | MUST be `"mconeworld-manifest"`, or the document is rejected as `manifest.schema.not_a_manifest` before anything else is read |
| `schemaVersion` | string | `"<major>.<minor>"`, digits without leading zeros. This document defines `"1.0"`. It is not the application version. |
| `archiveId` | uuid | Stable identity of this archive (sidecar plus world). It never changes. |
| `createdAt` | timestamp | When the archive was created |
| `revision` | integer ≥ 0 | Monotonic sidecar revision. Each committed change increases it by exactly 1. |
| `activeSeason` | uuid or null | The **explicitly selected** active season. It is never inferred from order or time. |
| `seasons` | array of season | Append (historical) order |
| `protectedRegions` | array of protectedRegion | Append order |
| `portalMarkers` | array of portalMarker | Append order |
| `backups` | array of backup | Append order |
| `playerArchives` | array of playerArchive | Append order |
| `files` | array of file | Sorted by `path` |
| `features` | object: key → feature | Keys sorted canonically |
| `extensions` | object | Optional. See §5. |

**season**

- `id`: uuid
- `name`: name
- `createdAt`: timestamp
- `status`: one of `"PLANNED"`, `"ACTIVE"`, `"ARCHIVED"`, `"CANCELLED"`
- `origins`: object mapping dimension → `{"x", "y", "z"}`. It may be empty, and any dimension may be absent. The End never inherits the Overworld origin.
- `spawn`: position
- `extensions`: optional

**protectedRegion**

- `id`: uuid
- `name`: name
- `area`: `{"dimension", "minX", "minZ", "maxX", "maxZ"}`, inclusive 32-bit chunk coordinates with min ≤ max
- `createdAt`: timestamp
- `extensions`: optional

**portalMarker**

- `id`: uuid
- `label`: name
- `destination`: position. It is recorded, not verified as safe, and grants no authorization.
- `destinationSeason`: uuid or null
- `createdAt`: timestamp
- `extensions`: optional

**backup**

- `id`: uuid
- `createdAt`: timestamp
- `checksum`: checksum
- `sizeBytes`: integer ≥ 0
- `operation`: uuid or null
- `activeSeason`: uuid or null
- `path`: path, which MUST lie under `backups/`
- `extensions`: optional

**playerArchive**

This is metadata only. Item data never appears in the manifest.

- `id`: uuid
- `player`: uuid, the existing account id
- `season`: uuid
- `archivedAt`: timestamp
- `categories`: non-empty array of distinct values from `INVENTORY`, `ENDER_CHEST`, `EXPERIENCE`, `ADVANCEMENTS`, `STATISTICS`, `RECIPES`, in that declaration order
- `physicalContainers`: array of position, in placement order
- `extensions`: optional

**file**

- `role`: `[a-z][a-z0-9-]{0,31}`, for example `"chunk-index"` or `"report"`
- `dimension`: dimension or null
- `path`: path
- `checksum`: checksum
- `sizeBytes`: integer ≥ 0
- `extensions`: optional

**feature**

- `adoptedAt`: timestamp
- `configurationVersion`: `[A-Za-z0-9][A-Za-z0-9._-]{0,31}`
- `extensions`: optional

A feature records *that a world-affecting feature was adopted*, for example global stronghold placement. It is history, not a live configuration switch.

Enum constant names are persisted verbatim and are case-sensitive.

## 4. Semantic rules

A reader reports all of the following. A document that violates any error rule is never returned.

| Rule | Code | Kind |
|---|---|---|
| Season ids are unique | `archive.season.duplicate_id` | REFERENTIAL |
| `activeSeason`, if non-null, names an existing season … | `archive.active_season.not_found` | REFERENTIAL |
| … whose status is `ACTIVE` | `archive.active_season.not_active` | REFERENTIAL |
| Every `ACTIVE` season is the referenced one | `archive.active_season.unreferenced` | REFERENTIAL |
| At most one season is `ACTIVE` | `archive.active_season.multiple` | REFERENTIAL |
| Ids are unique within regions, markers, backups, and player archives | `archive.<kind>.duplicate_id` | REFERENTIAL |
| `destinationSeason`, `backup.activeSeason`, and `playerArchive.season` name existing seasons | `archive.reference.unknown_season` | REFERENTIAL |
| Paths are unique across `files` and `backups` | `manifest.reference.duplicate_path` | REFERENTIAL |
| Every backup has a location, and every location has a backup | `manifest.reference.backup_path_missing` / `_unknown` | REFERENTIAL |
| No reference points at `manifest.json`; backups lie under `backups/` | `manifest.schema.invalid_value` | SCHEMA |
| Domain value rules (dimension grammar, name, checksum, coordinates within range, …) | `domain.*`, the code of `DomainErrorCode` | DOMAIN |
| Origin not aligned (Overworld 128 blocks, other dimensions 16) | `season.origin.misaligned` | **WARNING** |
| Overworld and Nether origins not exactly paired (÷8) | `season.origin.nether_unpaired` | **WARNING** |

Warnings never block decoding. They are returned in `DecodedManifest.warnings`.

## 5. Compatibility and extension policy

### 5.1 What a 1.0 reader accepts

| Input | Result |
|---|---|
| `schemaVersion` major ≠ 1 (e.g. `"0.9"`, `"2.0"`) | **Rejected**: `manifest.version.unsupported`. Fail closed: nothing else is interpreted or reported. |
| `schemaVersion` malformed (`"1"`, `"01.0"`, `"1.0.0"`) | Rejected: `manifest.schema.invalid_value` |
| `"1.0"` | Strict. Any member not defined above is rejected (`manifest.schema.unknown_member`). This catches typos. |
| `"1.N"`, N > 0 | Accepted, with a WARNING `manifest.version.newer_minor`. Every member defined above is fully validated. Unknown members in any object are **preserved** at their location and written back unchanged. |
| An `extensions` member on the root or on a record (season, region, marker, backup, player archive, file, feature) | Accepted in every 1.x version. It MUST be an object whose keys are namespaced keys. It is preserved verbatim and never interpreted. It is not allowed inside plain value objects such as positions, origins, and areas. |
| A new value for an enum member (e.g. a new `status`) | Rejected (`manifest.schema.invalid_value`). Adding enum constants therefore **requires a major version**. |

### 5.2 Minor-version evolution rules (for future schema authors)

A 1.x minor revision MAY only add optional members whose absence has the 1.0 meaning, and whose values stay valid when other 1.0 members are changed by an older writer. Anything else (new required members, new enum constants, changed meaning) is a major revision with an explicit migration (Prompt 06).

### 5.3 Read-modify-write

- A reader that decodes a 1.N file and writes it back keeps `schemaVersion` at `max("1.0", "1.N")`. It keeps every preserved member, and every `extensions` object, of each record that still exists.
- A record that is removed takes its preserved members with it.
- Preserved members inside `physicalContainers` are keyed by position in the array.
- Known members always take precedence over preserved ones.

### 5.4 Read-only guarantee

Decoding never modifies its input. `ManifestFiles.readOnly` opens the file for reading only, and never rewrites, normalizes, migrates, or repairs it, even when it is valid but non-canonical. Normalization happens only on the next explicit write.

## 6. Error categories

`ManifestException.kinds()` classifies every error by its code prefix. `primaryKind()` returns the first kind present, in this order:

1. `SYNTAX`: `sidecar.json.*`, except limits
2. `LIMIT`: `sidecar.json.limit.*`
3. `UNSUPPORTED_VERSION`: `manifest.version.*`
4. `SCHEMA`: `manifest.schema.*`, `sidecar.path.*`
5. `REFERENTIAL`: `manifest.reference.*`, `archive.*`
6. `DOMAIN`: `domain.*`, `season.*`

Syntax and limit failures stop decoding at the first error. All later stages collect every error, reported in deterministic order (severity, location, code, message). Locations are JSONPath-like, e.g. `$.seasons[2].colour`.

## 7. Change log

| Version | Change |
|---|---|
| 1.0 | Initial schema (Prompt 04) |
