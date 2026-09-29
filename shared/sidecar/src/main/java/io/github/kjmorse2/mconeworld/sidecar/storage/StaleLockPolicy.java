// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

/**
 * What to do when {@code writer.lock} exists but its owner is <em>provably</em> gone. That means:
 * the operating-system lock on the file is free, and the file contains complete owner metadata. The
 * owner only writes that metadata after it holds the OS lock, and the OS releases the lock when the
 * process dies. Staleness is never inferred from timestamps or process-id guesses.
 */
public enum StaleLockPolicy {
  /** Default: report {@code LOCK_STALE} with the old owner's metadata, and let a person decide. */
  REFUSE,
  /**
   * Take over a provably stale lock (for example, after an operator confirms the old process died).
   */
  TAKE_OVER_PROVABLY_STALE
}
