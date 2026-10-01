// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/**
 * Stable codes carried by {@link ChunkIndexException}. These are a public contract: never rename or
 * repurpose one. Codes for a persistent encoding's corruption and versioning are added with that
 * encoding (Prompt 09).
 */
public final class ChunkIndexIssueCodes {
  /**
   * The mutation needs an existing record, and the chunk has none. Activity and protection are
   * never recorded for a chunk without a first observation, because inventing one would be a guess.
   */
  public static final IssueCode RECORD_ABSENT = IssueCode.of("sidecar.index.record_absent");

  /** A replacement record does not describe the chunk it is filed under. */
  public static final IssueCode RECORD_MISMATCH = IssueCode.of("sidecar.index.record_mismatch");

  private ChunkIndexIssueCodes() {}
}
