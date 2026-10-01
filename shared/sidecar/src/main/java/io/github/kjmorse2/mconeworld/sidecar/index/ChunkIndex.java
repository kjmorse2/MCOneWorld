// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

/**
 * An open chunk index: snapshot readers plus at most one write batch at a time.
 *
 * <p>Ownership is explicit. Whoever opens the index closes it, which invalidates every reader,
 * cursor, and batch it handed out.
 *
 * <p>The contract (see the package documentation) is defined by {@link ChunkMutation}, {@link
 * ChunkQuery}, and {@link ChunkOrder}. The only implementation today is the in-memory reference in
 * {@code shared:test-fixtures}; the persistent encoding awaits ADR 0007.
 */
public interface ChunkIndex extends AutoCloseable {

  /** Opens a snapshot of the current committed state. The caller owns the reader. */
  ChunkIndexReader reader() throws ChunkIndexException;

  /**
   * Opens a write batch. The caller owns it.
   *
   * @throws IllegalStateException if another batch is open
   */
  ChunkIndexBatch begin() throws ChunkIndexException;

  /** Closes the index and invalidates everything it handed out. Idempotent; never throws. */
  @Override
  void close();
}
