// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * A chunk-index operation failed: a rejected mutation ({@link ChunkIndexIssueCodes}), or an I/O,
 * corruption, or version failure of a persistent implementation. Branch on {@link #code()}.
 *
 * <p>Programming errors, such as using a closed cursor or opening a second batch, are {@link
 * IllegalStateException}s instead.
 */
public class ChunkIndexException extends StorageException {
  @Serial private static final long serialVersionUID = 1L;

  public ChunkIndexException(IssueCode code, String message) {
    super(code, message);
  }

  public ChunkIndexException(IssueCode code, String message, @Nullable Throwable cause) {
    super(code, message, cause);
  }
}
