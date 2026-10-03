# Backups, format `mconeworld-backup` 1.0 (normative)

- Location: `mconeworld-data/backups/<backupId>/`
- Decision: [ADR 0012](../decisions/0012-backup-format.md)
- Implementation: `shared:sidecar`:
  - `sidecar.backup`: `BackupManifestCodec`, `BackupVerifier`, `BackupRecords`
  - `sidecar.storage`: `BackupEntryPath`, `BackupFileWriter`, `BackupFiles`
- Golden fixtures in `shared:test-fixtures`, `fixtures/backup/v1/`:
  - `full.json` (SHA-256 `7c8c681a941d992dd3c4b9532e4542b4b5b02907d810bfbc627fc68ca8ceab9a`)
  - `end-archive.json`

  Each has a `.meta` file.

## 1. Layout

```text
backups/<backupId>/
  backup.json             the backup manifest (this format); written last, at the commit point
  world/...               files copied from the world directory, at their relative paths
  sidecar/...             (full backups only) the sidecar's authoritative files
```

- **Areas.** `world/` mirrors the world directory and `sidecar/` mirrors the sidecar directory, each with the relative path unchanged. Restoring copies each file back to the same relative path.
- **What a full backup contains.** The sidecar manifest, the chunk-index area, and the operation journals. Never `backups/` itself.

## 2. Life cycle and commit point

1. **Write the payload.** The writer (holding the sidecar writer lock) creates `backups/<id>/`, which must not exist, then writes every payload file new, never overwriting, through `BackupFileWriter`. Each file is synced; `syncDirectories` then makes every new name durable.
2. **Verify.** `BackupVerifier.verifyPayload` re-reads every file against the in-memory manifest.
3. **Record.** `BackupRecords.stageAndRecord` stages `backup.json` in a sidecar transaction and adds a `BackupRecord` with:
   - checksum and size: those of `backup.json`
   - location: `backups/<id>/backup.json`
4. **Commit.** The transaction's commit is the **commit point**: it verifies `backup.json` on disk against the record, and only then does the backup exist for MCOneWorld.

**Interruption.** A backup directory without `backup.json` is **partial**. It is never referenced and never used. `BackupVerifier` reports it as `sidecar.backup.partial`. It is removed only by an explicit `BackupFileWriter.deletePartial`, which refuses a backup that has a `backup.json`.

## 3. `backup.json`

Canonical JSON (ADR 0003): UTF-8, members sorted, two-space indentation, a final newline. Every member is required.

| Member | Type | Meaning |
|---|---|---|
| `format` | string | `"mconeworld-backup"` |
| `version` | string | `"1.0"` |
| `id` | uuid | The backup's id. It equals its directory name. |
| `kind` | `"FULL"` or `"DIMENSION_ARCHIVE"` | A whole world plus the sidecar, or one dimension directory moved out by a season transition (ADR 0010) |
| `createdAt` | timestamp | When the copy was made (UTC ISO-8601, years 0000–9999) |
| `dimension` | dimension or null | The archived dimension. Non-null exactly for `DIMENSION_ARCHIVE`. |
| `sourceDataVersion` | integer ≥ 0 or null | The world's data version when copied. `null`: unknown. |
| `sidecarRevision` | integer ≥ 0 or null | The sidecar manifest revision when copied. `null`: the backup holds no sidecar files. |
| `totalBytes` | integer ≥ 0 | The sum of every entry's `sizeBytes`, a consistency check |
| `entries` | array of entry | Every file, in strictly ascending code-point order of `path` (the UTF-8 byte order), so paths are unique |

**entry**

- `path`: string, the file's path in the backup (§4)
- `sizeBytes`: integer ≥ 0
- `sha256`: checksum, `sha256:<64 lowercase hex>`

A `DIMENSION_ARCHIVE` holds `world/` entries only.

## 4. Entry paths

Names are kept **exactly** as the world has them; real worlds contain spaces, capitals, and parentheses, such as datapack archives and mod caches. The rules exclude only what is unsafe or ambiguous:

- The first segment is `world` or `sidecar`.
- 2 to 64 segments separated by `/`; at most 4096 UTF-8 bytes in total, and 255 per segment.
- No empty segment, no `.` or `..`, no backslash, no control character (U+0000–U+001F, U+007F), and no unpaired surrogate.

Reading and writing refuse symbolic links anywhere in a backup.

**Restoring onto another system.** A backup restored onto a filesystem with stricter rules may fail: Windows forbids `:` and reserved device names, and case-insensitive filesystems merge names that differ only in case. Such a failure is reported at restore time; a path is never renamed.

## 5. Verification

`BackupVerifier.verify(files, id, recordedChecksum)` reports every problem, each with a code:

| Code | Meaning |
|---|---|
| `sidecar.backup.partial` | No `backup.json` |
| `sidecar.backup.manifest_changed` | `backup.json` differs from the checksum the sidecar manifest records |
| `sidecar.backup.not_a_backup`, `sidecar.backup.unsupported_version`, `sidecar.backup.invalid` | `backup.json` cannot be read: wrong format, unsupported version, or invalid content. Also used when its `id` differs from the directory name. |
| `sidecar.backup.entry_missing` | A listed file is missing |
| `sidecar.backup.entry_changed` | A listed file's size or SHA-256 differs |
| `sidecar.backup.entry_unexpected` | A file present in the backup is not listed |

A backup is **verified** only with no findings at all.

## 6. Compatibility

| Input | Result |
|---|---|
| `"1.0"` | Strict: exactly the members above |
| `"1.N"`, N > 0 | Documented members validated; unknown members ignored. A backup is never rewritten, so nothing needs preserving. |
| Any other major | Refused: `sidecar.backup.unsupported_version` |

Adding a `kind` value requires a new major version.

## 7. Change log

| Version | Change |
|---|---|
| 1.0 | Initial format (Tool T4) |
