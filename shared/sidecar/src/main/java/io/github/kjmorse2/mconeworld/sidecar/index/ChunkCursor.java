// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import org.jspecify.annotations.Nullable;

/**
 * A forward-only stream of scan results, in {@link ChunkOrder}. The caller that opened it owns it
 * and must close it, normally with try-with-resources. An implementation may hold a file, a
 * statement, or a decoded region until then.
 *
 * <p>A cursor is not thread-safe. It is invalidated when it, its reader, or its index is closed;
 * using it afterwards throws {@link IllegalStateException}.
 */
public interface ChunkCursor extends AutoCloseable {

  /**
   * Returns the next record, or {@code null} once the results are exhausted.
   *
   * @throws ChunkIndexException if the record cannot be read, for example because the underlying
   *     data is corrupt
   * @throws IllegalStateException if the cursor was invalidated
   */
  @Nullable ChunkProvenance next() throws ChunkIndexException;

  /** Releases the cursor's resources. Idempotent; never throws. */
  @Override
  void close();
}
