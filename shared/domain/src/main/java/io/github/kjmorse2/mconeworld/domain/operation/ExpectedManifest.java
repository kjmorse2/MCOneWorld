// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import java.util.Objects;

/**
 * The sidecar manifest state an operation expects, or observed. Both parts of {@link AtRevision}
 * are compared: the revision catches ordinary concurrent commits, and the checksum catches a
 * manifest that was restored or edited at the same revision.
 */
public sealed interface ExpectedManifest {

  /** No {@code manifest.json} exists (the operation creates the first one). */
  record Absent() implements ExpectedManifest {}

  /**
   * The manifest is exactly this committed revision.
   *
   * @param revision the manifest revision, at least 0
   * @param checksum the checksum of the exact {@code manifest.json} bytes
   */
  record AtRevision(long revision, Checksum checksum) implements ExpectedManifest {
    /**
     * @throws IllegalArgumentException if {@code revision} is negative
     */
    public AtRevision {
      Objects.requireNonNull(checksum, "checksum");
      if (revision < 0) {
        throw new IllegalArgumentException("Manifest revision must not be negative");
      }
    }
  }
}
