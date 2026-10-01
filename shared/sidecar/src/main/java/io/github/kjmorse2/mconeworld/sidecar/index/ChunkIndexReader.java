// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkEnvelope;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import java.util.Optional;
import java.util.SortedSet;

/**
 * A read-only snapshot of an index: it sees the state committed when it was opened and nothing
 * later. The caller owns it and must close it; closing it invalidates every cursor it opened.
 *
 * <p>Readers may be used from one thread at a time. Open one reader per thread for parallel reads.
 *
 * <p>The default methods define {@link #contains}, {@link #count}, and {@link #envelope} in terms
 * of {@link #find} and {@link #scan}. Implementations may answer them faster, but never
 * differently.
 */
public interface ChunkIndexReader extends AutoCloseable {

  /** The dimensions that have at least one record, in {@link DimensionKey} order. */
  SortedSet<DimensionKey> dimensions() throws ChunkIndexException;

  /** The record of {@code chunk}, or empty if the index has none. */
  Optional<ChunkProvenance> find(ChunkPosition chunk) throws ChunkIndexException;

  /** Returns whether the index has a record for {@code chunk}. */
  default boolean contains(ChunkPosition chunk) throws ChunkIndexException {
    return find(chunk).isPresent();
  }

  /**
   * Opens a cursor over the records matching {@code query}, in {@link ChunkOrder}. The caller owns
   * the cursor.
   */
  ChunkCursor scan(ChunkQuery query) throws ChunkIndexException;

  /** The number of records matching {@code query}. */
  default long count(ChunkQuery query) throws ChunkIndexException {
    long count = 0;
    try (ChunkCursor cursor = scan(query)) {
      while (cursor.next() != null) {
        count++;
      }
    }
    return count;
  }

  /**
   * The bounding envelope of the records matching {@code query}. Like every envelope it is a
   * derived summary, never a membership set.
   */
  default ChunkEnvelope envelope(ChunkQuery query) throws ChunkIndexException {
    ChunkEnvelope envelope = ChunkEnvelope.empty(query.dimension());
    try (ChunkCursor cursor = scan(query)) {
      for (ChunkProvenance record = cursor.next(); record != null; record = cursor.next()) {
        envelope = envelope.including(record.chunk());
      }
    }
    return envelope;
  }

  /** Releases the snapshot and invalidates its cursors. Idempotent; never throws. */
  @Override
  void close();
}
