// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

/**
 * An atomic set of mutations. Nothing is visible to readers until {@link #commit()} returns, and
 * then everything is. Closing a batch that was not committed discards it.
 *
 * <p>Mutations apply in order through {@link ChunkMutation#applyTo}, each seeing the effect of the
 * ones before it. If one is rejected, nothing is applied for it and the batch is <b>poisoned</b>:
 * it can only be closed, and {@link #apply} and {@link #commit} throw {@link
 * IllegalStateException}. This keeps a half-validated batch from being committed by mistake.
 *
 * <p>A batch is not thread-safe, and at most one is open per index.
 */
public interface ChunkIndexBatch extends AutoCloseable {

  /**
   * Applies {@code mutation} to this batch's pending state.
   *
   * @throws ChunkIndexException if the mutation is rejected (the batch is then poisoned), or the
   *     existing record cannot be read
   * @throws IllegalStateException if the batch is committed, closed, or poisoned
   */
  void apply(ChunkMutation mutation) throws ChunkIndexException;

  /**
   * Makes every applied mutation durable and visible to readers opened afterwards. When this
   * returns, the batch survives a crash.
   *
   * @throws ChunkIndexException if the commit fails; the committed state is then unchanged
   * @throws IllegalStateException if the batch is committed, closed, or poisoned
   */
  void commit() throws ChunkIndexException;

  /** Discards the batch unless it was committed. Idempotent; never throws. */
  @Override
  void close();
}
