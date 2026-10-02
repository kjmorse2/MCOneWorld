// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * A chunk-index operation failed: a rejected mutation, or an I/O, corruption, or version failure.
 * Branch on {@link #code()} ({@link ChunkIndexIssueCodes}); {@link #scope()} names what is
 * affected, such as {@code segments/seg-0000000000000003.dat block minecraft:overworld r.-2.5 unit
 * 3} or {@code deltas-0000000000000007.log frame at 1204}, so the damage is never vaguer than it
 * needs to be.
 *
 * <p>Programming errors, such as using a closed cursor or opening a second batch, are {@link
 * IllegalStateException}s instead.
 */
public class ChunkIndexException extends StorageException {
  @Serial private static final long serialVersionUID = 1L;

  private final @Nullable String scope;

  public ChunkIndexException(IssueCode code, String message) {
    this(code, message, null, null);
  }

  public ChunkIndexException(IssueCode code, String message, @Nullable Throwable cause) {
    this(code, message, null, cause);
  }

  public ChunkIndexException(
      IssueCode code, String message, @Nullable String scope, @Nullable Throwable cause) {
    super(code, scope == null ? message : message + " [" + scope + "]", cause);
    this.scope = scope;
  }

  /** What the failure affects, or {@code null} if it is not about one stored structure. */
  public @Nullable String scope() {
    return scope;
  }
}
