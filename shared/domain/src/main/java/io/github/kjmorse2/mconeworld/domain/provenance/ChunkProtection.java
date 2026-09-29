// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.provenance;

/**
 * Explicit per-chunk protection, independent of provenance. A chunk may also be protected by
 * falling inside a {@link ProtectedRegion}. Callers combine the two. Constant names are persisted.
 */
public enum ChunkProtection {
  /** No explicit per-chunk protection. Region protection may still apply. */
  NONE,
  /** Destructive operations must not alter this chunk without an explicit override. */
  PROTECTED
}
