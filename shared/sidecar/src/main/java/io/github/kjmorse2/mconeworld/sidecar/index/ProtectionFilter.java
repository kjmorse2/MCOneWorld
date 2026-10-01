// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;

/**
 * Selects records by their <b>explicit per-chunk</b> protection. A chunk can also be protected by a
 * {@code ProtectedRegion} in the manifest, which the index does not know about: callers that decide
 * whether a chunk may be altered must check both.
 */
public enum ProtectionFilter {
  /** Every record. */
  ANY,
  /** Records with {@link ChunkProtection#PROTECTED}. */
  PROTECTED,
  /** Records with {@link ChunkProtection#NONE}. */
  UNPROTECTED;

  /** Returns whether {@code record} matches. */
  public boolean matches(ChunkProvenance record) {
    return switch (this) {
      case ANY -> true;
      case PROTECTED -> record.protection() == ChunkProtection.PROTECTED;
      case UNPROTECTED -> record.protection() == ChunkProtection.NONE;
    };
  }
}
