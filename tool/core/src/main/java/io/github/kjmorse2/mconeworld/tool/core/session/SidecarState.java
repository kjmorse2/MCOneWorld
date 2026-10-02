// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.session;

/** What is at the sidecar location. */
public enum SidecarState {
  /** Nothing: the directory does not exist. A sidecar can be initialized here. */
  ABSENT,
  /** An empty directory. A sidecar can be initialized here. */
  EMPTY,
  /** Files but no manifest: never initialized over (root §13); needs a person. */
  INCOMPLETE,
  /** A manifest this Tool reads. */
  PRESENT,
  /** A manifest of an older schema with a migration path; usable after an explicit migration. */
  NEEDS_MIGRATION,
  /** A manifest this Tool cannot read: corrupt, or a schema with no path. */
  UNREADABLE
}
