# Sidecar storage layout and bookkeeping files, version 1 (normative)

- Implementation: `shared:sidecar`, package `io.github.kjmorse2.mconeworld.sidecar.storage`
- Decision: [ADR 0004](../decisions/0004-sidecar-storage-durability-and-locking.md)
- JSON files here follow the encoding profile and canonical form of [manifest-v1.md §2](manifest-v1.md#2-encoding-profile).

## 1. Directory layout

```text
<sidecar root>/                      (caller-supplied; never discovered from global state)
├── manifest.json                    committed manifest (manifest-v1.md)
├── manifest.previous.json           the manifest that manifest.json replaced (last-known-good)
├── writer.lock                      permanent empty lock target; OS-locked by the writer
├── writer.owner.json                present only while a writer holds (or crashed holding) the lock
├── .staging/                        present only while a transaction runs, or after an interruption
│   └── <transaction-id>/
│       ├── transaction.json         durable intent (§3)
│       ├── data-<n>                 staged data files
│       ├── manifest.json            staged new manifest
│       └── manifest.previous.json   staged copy of the current manifest
└── <referenced data>                e.g. indexes/…, backups/…, reports/… (immutable once committed)
```

Reserved names, which may not be staged or referenced:

- `manifest.json`
- `manifest.previous.json`
- `writer.lock`
- `writer.owner.json`
- `writer.owner.json.tmp`
- `.staging` and anything under it

Symbolic links are not allowed anywhere below the root. The root itself may be reached through a symlink.

## 2. `writer.owner.json`

This is the format `mconeworld-writer-lock`, version 1. Example:

```json
{
  "acquiredAt": "2026-01-01T00:00:00Z",
  "format": "mconeworld-writer-lock",
  "host": "survival-server",
  "lockId": "0f8e2b64-6f3c-4c55-9d7c-2e2f0b0f6a11",
  "pid": 41237,
  "tool": "mconeworld-paper",
  "toolVersion": "0.1.0",
  "version": 1
}
```

- Text fields are bounded to 64 code points, and control characters are removed.
- The file never contains a command line, environment variables, user names, or paths.
- It is written atomically, via `writer.owner.json.tmp` and a rename, after the OS lock on `writer.lock` is acquired. It is deleted before the lock is released.

| `writer.lock` OS lock | `writer.owner.json` | Meaning |
|---|---|---|
| held | any | locked by a live writer (`LOCK_CONTENDED`) |
| free | absent | unlocked |
| free | valid | **provably stale**: the owner died holding the lock. `LOCK_STALE` under `REFUSE`; taken over only with `TAKE_OVER_PROVABLY_STALE`. |
| free | unreadable | unknown. It is refused (`LOCK_CONTENDED`) and never touched automatically. If no MCOneWorld process is running, an operator may delete it. |

## 3. `.staging/<id>/transaction.json`

This is the format `mconeworld-staging-transaction`, version 1.

| Member | Type | Meaning |
|---|---|---|
| `format`, `version` | string, integer | `"mconeworld-staging-transaction"`, `1` |
| `id` | uuid | Transaction id. It MUST equal the directory name. |
| `phase` | `"STAGING"` or `"PREPARED"` | STAGING: nothing outside staging has changed. PREPARED: staged files and the new manifest are durable, and files may already have been moved to their targets. |
| `baseManifest` | checksum or null | Checksum of `manifest.json` when the transaction began. Null if there was none. |
| `baseRevision` | integer or null | Its revision |
| `targetRevision` | integer | The revision being committed |
| `files` | array of `{stagedName, target, checksum, sizeBytes}` | Every staged data file and its final path |
| `targetManifest` | checksum or null | Checksum of the manifest to be committed (PREPARED only) |

## 4. Commit protocol

1. The writer holds the lock, and `inspect()` reports no findings. Leftovers must be recovered first (`RECOVERY_REQUIRED`).
2. `begin(expectedBaseRevision)` checks the current manifest revision (`BASE_CHANGED`). It creates `.staging/<id>/`, then writes and fsyncs `transaction.json` (STAGING).
3. `stage(target, stream, maxBytes)` streams into `data-<n>`, computing SHA-256 and size, then fsyncs.
4. `commit(document)`:
   1. Checks that the revision is base + 1 (0 for the first manifest), that the archive id is unchanged, and that no reserved or symlinked path is referenced.
   2. Encodes and validates the manifest.
   3. Re-reads every staged file and compares its checksum and size.
   4. Checks every staged file is referenced with exactly its checksum and size. Every other newly referenced file must already exist and match.
   5. Refuses to overwrite an existing different file at a target.
   6. Writes and fsyncs the staged manifest (and re-reads it byte-for-byte), then the staged previous manifest.
   7. Replaces `transaction.json` atomically with PREPARED.
   8. Moves data files to their targets, creating parent directories and syncing directories.
   9. **Commit point:** renames the previous-manifest copy to `manifest.previous.json`, then renames the staged manifest to `manifest.json`, then syncs the root directory.
   10. Removes the staging directory, deleting `transaction.json` last.
5. Any exception before the commit point rolls back: moved targets are deleted and staging is removed. If the rollback fails, recovery handles the leftovers. After the commit point, errors are only reported (`CommitResult.cleanupComplete`, `directorySyncSupported`).

## 5. Recovery classification

This is the output of `SidecarStore.inspect()`, which is read-only. Apply it with `SidecarWriter.recover()`, which is idempotent and requires the lock.

| Code | Condition | Automatic |
|---|---|---|
| `sidecar.recovery.committed_cleanup_pending` | PREPARED, and manifest = `targetManifest` | yes: remove staging |
| `sidecar.recovery.aborted_cleanup_pending` | manifest = `baseManifest`, or the staging dir is completely empty | yes: remove orphans (targets unreferenced by the current manifest whose content still matches), then staging |
| `sidecar.recovery.outcome_unknown` | manifest matches neither | **no** |
| `sidecar.recovery.unexplained_staging` | the entry has content but no readable, matching record | **no, never deleted** |
| `sidecar.recovery.manifest_unreadable` | `manifest.json` is missing, torn, or invalid | **no**. `manifest.previous.json` can be read with `readPreviousManifest()`. Restoring it is a deliberate operator or operation-journal action (Prompt 07). |

The report also carries the lock status, so a provably stale lock is visible.

## 6. Error codes

`StorageException.code()` is one of:

- `sidecar.storage.root_invalid`
- `symlink_refused`
- `path_escape`
- `reserved_path`
- `too_large`
- `checksum_mismatch`
- `atomic_move_unsupported`
- `lock_contended`
- `lock_stale`
- `lock_lost`
- `base_changed`
- `data_file_conflict`
- `reference_unverified`
- `transaction_invalid`
- `manifest_invalid`
- `recovery_required`
- `io_failure`

Every code after the first has the `sidecar.storage.` prefix.

## 7. Platform support and limitations

- **Tested:** Linux, ext4, local disk, JDK 25.
- **Expected but untested:** macOS (APFS) and other POSIX local filesystems.
- **Windows:** directory fsync is unavailable (reported through `directorySyncSupported=false`). File-lock semantics differ, and nothing is tested there. No support is claimed.
- **Network filesystems (NFS, SMB, and similar):** unsupported. OS locks and rename atomicity are not reliable there.
- **Durability** is only as good as the storage stack's honouring of `fsync`. Disks or virtualisation layers that ignore flushes can lose recent commits, but never tear a manifest.
