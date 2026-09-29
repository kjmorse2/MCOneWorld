# ADR 0004: Sidecar storage durability, atomicity, and locking

- Status: **Accepted**, 2026-09-28. Accepted by the implementing agent under explicit owner delegation.
- Milestone: Prompt 05
- Normative format: [`docs/formats/sidecar-storage-v1.md`](../formats/sidecar-storage-v1.md)

## Context

The sidecar is the authoritative MCOneWorld history (root `AGENTS.md` §2, §9). A crash, power loss, full disk, or concurrent writer must never destroy the last valid manifest, and every interrupted write must be detectable and recoverable without guessing. The root instructions also forbid:

- implicit repair
- automatic deletion of unexplained files
- cross-platform guarantees that tests do not support

## Decision

### 1. Single commit point

Every change is one transaction.

- All new content is staged under `<root>/.staging/<transaction-id>/`. That is inside the sidecar root, so it is on the same filesystem.
- The **only** commit point is one atomic rename: the staged manifest replaces `manifest.json`.
- Before the commit point, the previous manifest is moved atomically to `manifest.previous.json`, the last-known-good fallback.

### 2. Immutable data files

- A file referenced by a committed manifest is never overwritten or modified in place.
- New content goes to a new path; the caller chooses revision-suffixed or content-addressed names.
- Staging different content at an existing path is refused (`DATA_FILE_CONFLICT`). Re-staging identical content is an idempotent no-op.
- Unreferenced old data files are kept. Removing them is a future, explicit garbage-collection operation.

This is what lets the previous manifest stay fully valid until the rename.

### 3. Two-phase durable intent

`transaction.json` records the transaction's intent in two phases:

- **STAGING**, at begin
- **PREPARED**, replaced atomically once every staged file and the new manifest are durable, and *before* any file leaves staging. It lists every target path, checksum, and size, plus the base and target manifest checksums.

Recovery classifies each leftover by comparing the current `manifest.json` checksum with the recorded base and target checksums.

| Evidence | Classification | Automatic action |
|---|---|---|
| PREPARED, and manifest = target | committed | remove staging |
| manifest = base (any phase) | aborted | remove staging, plus orphans: targets that the current manifest does not reference and whose content still matches the record |
| manifest matches neither | outcome unknown | none: a person decides |
| no readable record, but content present | unexplained | none, and never deleted |
| completely empty staging directory | aborted (crash before the record) | remove it |

Recovery only runs when explicitly called (`SidecarWriter.recover()`), under the writer lock. It is idempotent.

### 4. Durability policy

- Every staged file, the staged manifest, and the intent are written, then `fsync`ed (`FileChannel.force(true)`), then closed, before the next step. An fsync failure aborts the transaction.
- Staged content is **re-read and checksum-verified from disk** before the commit point. The staged manifest is re-read and compared byte-for-byte.
- Directory entries (creations and renames) are synced by `fsync` on the directory where the platform allows it. Where it does not (known: Windows, where Java cannot open a directory channel), the commit still proceeds, and `CommitResult.directorySyncSupported` is `false`. Consequence: after a power loss shortly after the commit, the sidecar may revert to the previous manifest. It can never become torn, because the rename is atomic.
- Failures after the commit point (directory sync, cleanup) never undo the commit. They are reported: `directorySyncSupported`, `cleanupComplete`.

### 5. Atomic rename is required

- If the filesystem cannot rename atomically (`AtomicMoveNotSupportedException`), the write is **refused** (`ATOMIC_MOVE_UNSUPPORTED`).
- There is no copy-and-delete fallback, because that could leave a torn or missing manifest.
- Mainstream local filesystems (ext4, XFS, Btrfs, APFS, NTFS) support atomic same-directory renames. Only ext4-on-Linux is tested.

### 6. Writer lock

- `writer.lock` is a **permanent**, empty lock-target file. It is created once and never deleted. Holding the lock means holding an OS `FileLock` on it.
- The owner's metadata is written to `writer.owner.json` *after* the OS lock is acquired, atomically (temporary file then rename), and deleted *before* the lock is released.
- The metadata contains no secrets: format, version, lockId, tool, toolVersion, host, pid, acquiredAt. It never contains a command line, environment, user name, or path.
- **Provably stale** means: OS lock free *and* owner file present. The OS releases a dead process's locks, and the owner file is deleted before a normal release, so this state arises only when the owner died while holding the lock.
  - Timestamps, PID liveness, and age are never used.
  - The default policy is `REFUSE`, which reports `LOCK_STALE` with the dead owner's metadata. `TAKE_OVER_PROVABLY_STALE` must be requested explicitly.
  - An unreadable owner file is never treated as stale: it needs a person.
- **POSIX pitfall.** Java file locks are `fcntl` record locks. They belong to the process, and closing *any* descriptor of the file releases them. So within a JVM that holds the lock, `writer.lock` is never opened again. A JVM-wide registry answers both "held by this JVM?" for a second acquisition and `inspect()`. This bug was found during implementation by a cross-process test. The test is kept as a regression guard, and was mutation-tested: removing the registry makes it fail.
- `verifyHeld()` runs before every mutation. It detects a lost OS lock, and a removed or replaced owner file (`LOCK_LOST`).

### 7. Paths and symlinks

- The sidecar root may be reached through symlinks. It is canonicalized once, when opened.
- Below the root, **no path component may be a symbolic link**. `SidecarRoot.resolve` refuses such paths (`SYMLINK_REFUSED`), because a link could redirect a write outside the sidecar or into a world directory.
- Reserved names cannot be staged or referenced: `manifest.json`, `manifest.previous.json`, `writer.lock`, `writer.owner.json(.tmp)`, `.staging/`.
- The check runs at resolution time. A link created afterwards by an uncooperative process is out of scope.

### 8. Read-only operations write nothing

`readManifest`, `readPreviousManifest`, `inspect`, and `SidecarLock.inspect` create, modify, rename, and delete nothing. Tests count every mutating call through the fault-injection seam.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Write the manifest in place (truncate and write) | A crash tears the only copy |
| Copy fallback when atomic rename is unsupported | It can tear or lose the manifest; refusing is safer |
| Lock file with PID and age heuristics for staleness | PIDs are reused and clocks drift: that is guessing |
| Delete-on-release lock file (a single file holds both the OS lock and the metadata) | Implemented first. Reading or verifying it inside the holder dropped the POSIX lock, and deleting and recreating it allows inode races. |
| Embedded database (SQLite or H2) for the manifest | Not human-reviewable. It would prejudge the chunk-index ADR (Prompt 08). |
| Automatic recovery on open | It violates "read-only open never repairs" |

## Consequences

- Every write costs several fsyncs. That is acceptable for low-volume manifest commits. Chunk-index write patterns are evaluated in Prompt 08.
- Disk usage grows until an explicit data-file GC exists. It is a future operation, and must itself run through this transaction protocol.
- A sidecar on NFS or SMB is unsupported, because OS locks are unreliable there. Document this for operators.

## Testing

See `shared/sidecar/src/test/java/.../storage/`:

- `CommitFaultTest`: an I/O failure *and* a simulated crash at each of the 7 `CommitStage`s (14 cases). They assert that the previous committed manifest stays readable, that leftovers are detected, that recovery is idempotent, and that every handle is closed.
- `StorageBehaviourTest`: checksum corruption before verification, reference mismatch, unverified references, immutability conflict, reserved paths, base and revision checks, full disk, unsupported atomic move, unexplained and ambiguous leftovers, damaged manifest with a readable previous one, symlink escape, a root reached through a symlink, missing permissions (POSIX, non-root), zero writes and closed handles for read-only operations, and bounded checksums.
- `SidecarLockTest`:
  - in-JVM contention
  - 16 threads × 20 repetitions: exactly one winner
  - stale refusal and take-over
  - unreadable metadata
  - lost lock
  - metadata privacy
  - holder-side inspection
  - **cross-process** exclusion, and a killed process leaving a provably stale lock
- The whole sidecar suite was run 10 times in a row from scratch with no failures (Prompt 05 report).
