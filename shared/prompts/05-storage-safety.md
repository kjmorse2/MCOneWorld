# Prompt 05 — Sidecar Storage Safety

Implement the filesystem safety layer for the v1 sidecar without changing manifest semantics.

## Objective

Provide reusable, platform-neutral primitives for safe relative paths, checksums, bounded reads, writer exclusion, staged writes, atomic replacement, and interrupted-write detection.

## Required capabilities

- Resolve manifest-relative paths beneath an explicit sidecar root.
- Reject absolute paths, traversal, normalization escapes, reserved/ambiguous names where relevant, and symlink escapes according to a documented policy.
- Stream algorithm-qualified checksums.
- Read with explicit byte limits.
- Stage new data in the destination filesystem.
- Flush and sync according to the approved durability policy.
- Atomically replace where supported, with a documented safe fallback or explicit refusal where not.
- Acquire an exclusive sidecar writer lock with owner/process metadata that does not expose secrets.
- Detect contention and apply the approved stale-lock policy without guessing.
- Preserve the last known-good manifest until all referenced staged files validate.
- Surface structured recovery information after interruption.

## Test strategy

Build a fault-injection seam around filesystem operations. Simulate failure:

- Before staging
- During content write
- Before checksum verification
- Before sync
- Before manifest replacement
- Immediately after manifest replacement
- During cleanup

Test path traversal, symlink escape, lock contention, stale-lock scenarios, checksum mismatch, insufficient permissions, unavailable atomic move, disk/full-write simulation where the abstraction permits, and repeated recovery.

Tests must prove:

- The previous committed state remains readable after every pre-commit failure.
- Temporary artifacts are detectable and recoverable.
- Recovery is idempotent.
- A read-only open performs no writes.
- Two writers cannot both believe they hold the lock.
- File handles close on success and failure.

## Constraints

- Do not add world-file operations.
- Do not use a global sidecar path.
- Do not auto-delete unexplained temporary or lock files.
- Do not claim cross-platform guarantees that tests or documentation do not support.

## Acceptance criteria

All failure points have tests, durability limitations are explicit, and no failed operation destroys the last valid manifest. Run the relevant suite repeatedly to identify flaky filesystem timing assumptions. Stop after reporting evidence.

