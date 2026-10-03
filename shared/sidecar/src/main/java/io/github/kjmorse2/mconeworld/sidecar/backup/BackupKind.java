// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.backup;

/** What a backup holds. Persisted verbatim; adding a constant requires a new major version. */
public enum BackupKind {
  /** The whole world plus the sidecar's authoritative files: restorable on its own. */
  FULL,
  /** One dimension's directory, moved out of the world by a season transition (ADR 0010). */
  DIMENSION_ARCHIVE
}
