// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkCursor;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndex;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexBatch;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The <b>reference</b> {@link ChunkIndex}: deliberately simple, in memory, and obviously correct,
 * so that every other implementation can be checked against it ({@link ChunkIndexDifferential}). It
 * is not a candidate encoding and must never be optimized; clarity is its only job.
 *
 * <ul>
 *   <li>Committed state is one {@link HashMap}. A reader copies it, so later commits cannot leak
 *       into a snapshot.
 *   <li>A scan filters every record through {@link ChunkQuery#matches} and sorts by {@link
 *       ChunkOrder#COMPARATOR}.
 *   <li>A batch keeps pending records in its own map and applies every mutation through {@link
 *       ChunkMutation#applyTo}.
 * </ul>
 *
 * <p>Memory and time are linear in the number of records per reader and per scan, so use it only
 * for test-sized data. It is not thread-safe.
 */
public final class ReferenceChunkIndex implements ChunkIndex {
  private final Map<ChunkPosition, ChunkProvenance> committed = new HashMap<>();
  private @Nullable Batch openBatch;
  private boolean closed;

  @Override
  public ChunkIndexReader reader() {
    requireOpen();
    return new Reader(new HashMap<>(committed));
  }

  @Override
  public ChunkIndexBatch begin() {
    requireOpen();
    if (openBatch != null) {
      throw new IllegalStateException("Another batch is open; an index has one writer at a time");
    }
    Batch batch = new Batch();
    openBatch = batch;
    return batch;
  }

  @Override
  public void close() {
    closed = true;
    openBatch = null;
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("The index is closed");
    }
  }

  private final class Reader implements ChunkIndexReader {
    private final Map<ChunkPosition, ChunkProvenance> snapshot;
    private boolean readerClosed;

    Reader(Map<ChunkPosition, ChunkProvenance> snapshot) {
      this.snapshot = snapshot;
    }

    @Override
    public SortedSet<DimensionKey> dimensions() {
      requireUsable();
      SortedSet<DimensionKey> dimensions = new TreeSet<>();
      for (ChunkPosition chunk : snapshot.keySet()) {
        dimensions.add(chunk.dimension());
      }
      return dimensions;
    }

    @Override
    public Optional<ChunkProvenance> find(ChunkPosition chunk) {
      requireUsable();
      return Optional.ofNullable(snapshot.get(chunk));
    }

    @Override
    public ChunkCursor scan(ChunkQuery query) {
      requireUsable();
      List<ChunkProvenance> results =
          snapshot.values().stream()
              .filter(query::matches)
              .sorted((a, b) -> ChunkOrder.COMPARATOR.compare(a.chunk(), b.chunk()))
              .toList();
      return new Cursor(this, results.iterator());
    }

    @Override
    public void close() {
      readerClosed = true;
    }

    void requireUsable() {
      requireOpen();
      if (readerClosed) {
        throw new IllegalStateException("The reader is closed");
      }
    }
  }

  private static final class Cursor implements ChunkCursor {
    private final Reader reader;
    private final Iterator<ChunkProvenance> results;
    private boolean cursorClosed;

    Cursor(Reader reader, Iterator<ChunkProvenance> results) {
      this.reader = reader;
      this.results = results;
    }

    @Override
    public @Nullable ChunkProvenance next() {
      reader.requireUsable();
      if (cursorClosed) {
        throw new IllegalStateException("The cursor is closed");
      }
      return results.hasNext() ? results.next() : null;
    }

    @Override
    public void close() {
      cursorClosed = true;
    }
  }

  private final class Batch implements ChunkIndexBatch {
    /** Pending records; a {@code null} value means "removed in this batch". */
    private final Map<ChunkPosition, @Nullable ChunkProvenance> pending = new HashMap<>();

    private boolean done;
    private boolean poisoned;

    @Override
    public void apply(ChunkMutation mutation) throws ChunkIndexException {
      requireWritable();
      ChunkPosition chunk = mutation.chunk();
      @Nullable ChunkProvenance existing =
          pending.containsKey(chunk) ? pending.get(chunk) : committed.get(chunk);
      try {
        pending.put(chunk, mutation.applyTo(existing));
      } catch (ChunkIndexException e) {
        poisoned = true;
        throw e;
      }
    }

    @Override
    public void commit() {
      requireWritable();
      for (Map.Entry<ChunkPosition, @Nullable ChunkProvenance> entry : pending.entrySet()) {
        ChunkProvenance record = entry.getValue();
        if (record == null) {
          committed.remove(entry.getKey());
        } else {
          committed.put(entry.getKey(), record);
        }
      }
      finish();
    }

    @Override
    public void close() {
      if (!done) {
        finish();
      }
    }

    private void finish() {
      done = true;
      pending.clear();
      if (openBatch == this) {
        openBatch = null;
      }
    }

    private void requireWritable() {
      requireOpen();
      if (done) {
        throw new IllegalStateException("The batch is already committed or closed");
      }
      if (poisoned) {
        throw new IllegalStateException("The batch was poisoned by a rejected mutation; close it");
      }
    }
  }
}
