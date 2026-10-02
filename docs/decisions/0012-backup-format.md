# ADR 0012: Backups in the sidecar

- Status: **Accepted**, 2026-10-02, by the project owner (tool-planning session): backups live inside the sidecar's `backups/` area. The byte-level format is specified in milestone T4 (`docs/formats/backup-v1.md`), and this ADR is updated with its test references then.
- See [`tool-implementation-plan.md`](../architecture/tool-implementation-plan.md).

## 1. Context

Root §10 requires a verified backup before every destructive operation, and §9 requires the sidecar to hold no absolute paths. Manifest 1.0 already records backups as `BackupRecord`s (id, creation time, checksum, size, operation, active season), each with a required path under `backups/` (ADR 0003 and Prompt 04). The Tool needs two kinds of backup:

- **full:** the world plus the sidecar's authoritative files, taken before a season transition or on request
- **dimension archive:** the End files moved out by a season transition (ADR 0010), kept as history

## 2. Decision

1. **Location.** Each backup is a directory `backups/<backupId>/` inside the sidecar. Its contents are written through a reserved-area writer in `shared:sidecar` (modelled on `IndexFileWriter`), which holds the writer lock, refuses symlinks, and resolves every path safely.
2. **Contents.** The copied files sit under `world/…` and `sidecar/…` with their relative paths unchanged. A backup manifest, `backup.json` (format `mconeworld-backup` 1.0, canonical JSON), lists:
   - its kind (`full` or `dimension-archive`)
   - its source: data version, dimension, and the sidecar revision
   - every file, with its relative path, size, and SHA-256
3. **Sidecar copy.** A full backup copies the manifest, the chunk-index area, and the operation journals. It never copies `backups/` itself.
4. **Writing:**
   1. Check free space against the previewed size, with headroom; refuse if it is short.
   2. Copy the files, fsyncing each one.
   3. Write `backup.json` last, through a temp file and an atomic rename.
   4. Re-read every file and compare it with the manifest (**verification**).
   5. Only a verified backup is recorded in the sidecar manifest, as a `BackupRecord` whose checksum is the SHA-256 of `backup.json`.
5. **Partial backups.** A backup directory with no `backup.json`, or one that fails verification, is reported as partial and never used. It is removed only on explicit request.
6. **Restore** is deferred to a later milestone. Until then, the Tool shows exact manual restoration steps generated from `backup.json`. Rollback of an operation uses that operation's own backup.

## 3. Alternatives

| Alternative | Why not |
|---|---|
| Backups in a user-chosen external folder | Would need location records outside the sidecar's portable, relative-path model. The user controls where the whole sidecar lives instead. |
| One compressed archive per backup (zip or tar) | One damaged byte affects the whole archive, and copying a file tree lets individual files be verified and restored. Compression can come later as a new format version. |

## 4. Consequences

- **Size.** Backups can be as large as the world. The preview shows the expected size, and the free-space check refuses a backup that can't fit.
- **Cleanup.** Deleting old backups is an explicit, confirmed operation; nothing prunes automatically.
- **Same filesystem.** The world and the sidecar may be on different filesystems, so every copy is copy-then-verify. No "move" ever happens across filesystems.

## 5. Migration impact

- Adds the `mconeworld-backup` 1.0 format.
- Adds archived-dimension references in manifest 1.1 (ADR 0010).
- Existing 1.0 `BackupRecord`s keep their meaning.

## 6. How it is tested

Milestone T4 (format) and T7 (service):

- golden `backup.json`
- verification catching a flipped byte, a missing file, and an extra file
- interruption at every step, leaving the backup either recorded and verified or reported as partial
- a full disk
- symlink refusal
- recursion guard: `backups/` is never copied into itself
