// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import org.jspecify.annotations.Nullable;

/** Spike-local issue codes. Not a contract; the production format defines its own. */
final class SpikeCodes {
  /** Stored data failed a checksum, magic, or structural check. */
  static final IssueCode CORRUPT = IssueCode.of("sidecar.index.spike.corrupt");

  /** An I/O or library failure that is not known to be corruption. */
  static final IssueCode IO = IssueCode.of("sidecar.index.spike.io");

  private SpikeCodes() {}

  static ChunkIndexException corrupt(String message) {
    return new ChunkIndexException(CORRUPT, message);
  }

  static ChunkIndexException corrupt(String message, @Nullable Throwable cause) {
    return new ChunkIndexException(CORRUPT, message, cause);
  }

  static ChunkIndexException io(String message, @Nullable Throwable cause) {
    return new ChunkIndexException(IO, message, cause);
  }
}
