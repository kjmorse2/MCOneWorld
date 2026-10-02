// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.session;

/** The things a user can start from the status screen. */
public enum SessionAction {
  /** Create a sidecar and record the world's chunks as legacy provenance (T5). */
  INITIALIZE_SIDECAR,
  /** Resolve leftovers of an interrupted sidecar commit, explicitly (shared recovery). */
  RECOVER_SIDECAR,
  /** Migrate an older manifest to the current schema, explicitly (ADR 0005). */
  MIGRATE_MANIFEST,
  /** Bring the chunk index in line with the world after external trimming (T6). */
  RECONCILE,
  /** Create and verify a backup of the world and the sidecar (T7). */
  BACKUP,
  /** Create and activate the next season, archiving the End (T9). */
  NEW_SEASON
}
