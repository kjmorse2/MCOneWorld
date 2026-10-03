// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.inventory;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkCursor;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares the chunks of a world with the records of a chunk index, dimension by dimension, as a
 * merge of two sorted streams: the world's {@link ChunkOrder} keys and the index's cursor, which
 * yields records in the same order. Read-only; memory is the world's key arrays only.
 */
public final class IndexComparison {
  /** Receives the differences, in dimension order and then {@link ChunkOrder}. */
  public interface Listener {
    /** A chunk the world has and the index does not. */
    void added(ChunkPosition chunk) throws IOException;

    /** A record of a chunk the world no longer has. */
    void removed(ChunkProvenance record) throws IOException;
  }

  /** Counts the differences. */
  public static final class Counter implements Listener {
    private long added;
    private long removed;

    @Override
    public void added(ChunkPosition chunk) {
      added++;
    }

    @Override
    public void removed(ChunkProvenance record) {
      removed++;
    }

    public long added() {
      return added;
    }

    public long removed() {
      return removed;
    }

    public boolean same() {
      return added == 0 && removed == 0;
    }
  }

  private IndexComparison() {}

  /**
   * Reports every difference between {@code world} and {@code index}.
   *
   * @throws InterruptedIOException if the thread is interrupted
   */
  public static void compare(WorldChunks world, ChunkIndexReader index, Listener listener)
      throws IOException {
    Set<DimensionKey> dimensions = new TreeSet<>(world.keys().keySet());
    dimensions.addAll(index.dimensions());
    for (DimensionKey dimension : dimensions) {
      long[] keys = world.keys().getOrDefault(dimension, new long[0]);
      int i = 0;
      try (ChunkCursor cursor = index.scan(ChunkQuery.all(dimension))) {
        for (ChunkProvenance record = cursor.next(); record != null; record = cursor.next()) {
          long key = ChunkOrder.key(record.chunk());
          while (i < keys.length && keys[i] < key) {
            listener.added(ChunkOrder.position(dimension, keys[i++]));
          }
          if (i < keys.length && keys[i] == key) {
            i++;
          } else {
            listener.removed(record);
          }
          if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("The comparison was interrupted");
          }
        }
      }
      while (i < keys.length) {
        listener.added(ChunkOrder.position(dimension, keys[i++]));
      }
    }
  }
}
