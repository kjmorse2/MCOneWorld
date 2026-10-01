// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkEnvelope;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkCursor;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexBatch;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The lifecycle rules every candidate shares (one batch at a time, invalidation on close, poisoned
 * batches), so that candidates differ only in storage. Mirrors {@code ReferenceChunkIndex}.
 */
abstract class SpikeIndexBase implements SpikeIndex {
  private @Nullable Batch openBatch;
  private volatile boolean closed;

  @Override
  public final ChunkIndexBatch begin() throws ChunkIndexException {
    requireOpen();
    if (openBatch != null) {
      throw new IllegalStateException("Another batch is open; an index has one writer at a time");
    }
    Batch batch = new Batch();
    openBatch = batch;
    return batch;
  }

  @Override
  public final void close() {
    if (!closed) {
      closed = true;
      openBatch = null;
      closeStorage();
    }
  }

  final void requireOpen() {
    if (closed) {
      throw new IllegalStateException("The index is closed");
    }
  }

  /**
   * The committed record of {@code chunk}, for applying mutations. Called only while a batch is
   * open.
   */
  abstract @Nullable ChunkProvenance committedRecord(ChunkPosition chunk)
      throws ChunkIndexException;

  /**
   * Durably commits {@code changes} (a {@code null} value removes the chunk). On failure the
   * committed state must be unchanged.
   */
  abstract void commitChanges(Map<ChunkPosition, @Nullable ChunkProvenance> changes)
      throws ChunkIndexException;

  /** Releases storage. Called once. */
  abstract void closeStorage();

  /**
   * Whether batches record the mutations themselves instead of resolved records. Every mutation's
   * validity depends only on whether the chunk has a record ({@link ChunkMutation#applyTo} rejects
   * only {@code RecordActivity} and {@code SetProtection} without one), so a deferred batch
   * validates with {@link #committedExists} and never reads a record. The candidate then folds the
   * mutations through {@code applyTo} when it reads or checkpoints.
   */
  boolean deferredMutations() {
    return false;
  }

  /** Whether {@code chunk} has a committed record. Deferred candidates answer without decoding. */
  boolean committedExists(ChunkPosition chunk) throws ChunkIndexException {
    return committedRecord(chunk) != null;
  }

  /** Durably commits deferred mutations, in order per chunk. Only for deferred candidates. */
  void commitMutations(Map<ChunkPosition, List<ChunkMutation>> mutations)
      throws ChunkIndexException {
    throw new UnsupportedOperationException("not a deferred candidate");
  }

  /** Whether a record exists after {@code mutation}, given whether one existed before. */
  static boolean existsAfter(ChunkMutation mutation, boolean before) {
    return switch (mutation) {
      case ChunkMutation.ObserveFirst o -> true;
      case ChunkMutation.Replace r -> true;
      case ChunkMutation.Remove r -> false;
      case ChunkMutation.RecordActivity a -> before;
      case ChunkMutation.SetProtection p -> before;
    };
  }

  /** A reader whose usability also depends on the index being open. */
  abstract class ReaderBase implements ChunkIndexReader {
    private boolean readerClosed;

    final void requireUsable() {
      requireOpen();
      if (readerClosed) {
        throw new IllegalStateException("The reader is closed");
      }
    }

    @Override
    public final void close() {
      if (!readerClosed) {
        readerClosed = true;
        release();
      }
    }

    /** Releases the snapshot. Called once. */
    abstract void release();

    /** The contract's default {@code count}, for overrides that fall back to scanning. */
    final long countByScan(ChunkQuery query) throws ChunkIndexException {
      long count = 0;
      try (ChunkCursor cursor = scan(query)) {
        while (cursor.next() != null) {
          count++;
        }
      }
      return count;
    }

    /** The contract's default {@code envelope}, for overrides that fall back to scanning. */
    final ChunkEnvelope envelopeByScan(ChunkQuery query) throws ChunkIndexException {
      ChunkEnvelope envelope = ChunkEnvelope.empty(query.dimension());
      try (ChunkCursor cursor = scan(query)) {
        for (ChunkProvenance r = cursor.next(); r != null; r = cursor.next()) {
          envelope = envelope.including(r.chunk());
        }
      }
      return envelope;
    }
  }

  /** A cursor that checks its reader before every step. */
  abstract static class CursorBase implements ChunkCursor {
    private final ReaderBase reader;
    private boolean cursorClosed;

    CursorBase(ReaderBase reader) {
      this.reader = reader;
    }

    @Override
    public final @Nullable ChunkProvenance next() throws ChunkIndexException {
      reader.requireUsable();
      if (cursorClosed) {
        throw new IllegalStateException("The cursor is closed");
      }
      return advance();
    }

    @Override
    public final void close() {
      if (!cursorClosed) {
        cursorClosed = true;
        release();
      }
    }

    abstract @Nullable ChunkProvenance advance() throws ChunkIndexException;

    void release() {}
  }

  private final class Batch implements ChunkIndexBatch {
    private final Map<ChunkPosition, @Nullable ChunkProvenance> pending = new HashMap<>();
    private final Map<ChunkPosition, List<ChunkMutation>> deferred = new LinkedHashMap<>();
    private final Map<ChunkPosition, Boolean> exists = new HashMap<>();
    private boolean done;
    private boolean poisoned;

    @Override
    public void apply(ChunkMutation mutation) throws ChunkIndexException {
      requireWritable();
      if (deferredMutations()) {
        applyDeferred(mutation);
        return;
      }
      ChunkPosition chunk = mutation.chunk();
      @Nullable ChunkProvenance existing;
      if (pending.containsKey(chunk)) {
        existing = pending.get(chunk);
      } else if (mutation instanceof ChunkMutation.Replace
          || mutation instanceof ChunkMutation.Remove) {
        // Their result does not depend on the existing record of the same chunk, so skip the read.
        existing = null;
      } else {
        existing = committedRecord(chunk);
      }
      try {
        pending.put(chunk, mutation.applyTo(existing));
      } catch (ChunkIndexException e) {
        poisoned = true;
        throw e;
      }
    }

    private void applyDeferred(ChunkMutation mutation) throws ChunkIndexException {
      ChunkPosition chunk = mutation.chunk();
      Boolean pendingExists = exists.get(chunk);
      boolean before = pendingExists != null ? pendingExists : committedExists(chunk);
      if (!before && !existsAfter(mutation, false) && !(mutation instanceof ChunkMutation.Remove)) {
        try {
          mutation.applyTo(null); // throws the contract's own record_absent rejection
        } catch (ChunkIndexException e) {
          poisoned = true;
          throw e;
        }
      }
      deferred.computeIfAbsent(chunk, c -> new ArrayList<>()).add(mutation);
      exists.put(chunk, existsAfter(mutation, before));
    }

    @Override
    public void commit() throws ChunkIndexException {
      requireWritable();
      if (deferredMutations()) {
        commitMutations(deferred);
      } else {
        commitChanges(pending);
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
      deferred.clear();
      exists.clear();
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
