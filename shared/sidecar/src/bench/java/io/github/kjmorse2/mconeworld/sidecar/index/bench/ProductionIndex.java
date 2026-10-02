// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.bench;

import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexBatch;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexOptions;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStore;
import io.github.kjmorse2.mconeworld.sidecar.index.SidecarChunkIndex;
import io.github.kjmorse2.mconeworld.sidecar.storage.LockOwner;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/**
 * The production index of a sidecar directory, with its writer lock, as one closeable handle. The
 * crash probe kills writers mid-commit, so a provably stale lock (the OS lock is free) is taken
 * over; that is the documented recovery for a dead writer.
 */
final class ProductionIndex implements BenchIndex {
  private final SidecarWriter writer;
  private final SidecarChunkIndex index;

  private ProductionIndex(SidecarWriter writer, SidecarChunkIndex index) {
    this.writer = writer;
    this.index = index;
  }

  static ProductionIndex open(Path directory, ChunkIndexOptions options)
      throws ChunkIndexException {
    try {
      Files.createDirectories(directory);
      SidecarStore store = SidecarStore.open(SidecarRoot.open(directory));
      SidecarWriter writer =
          store.openWriter(
              new LockOwner("chunk-index-bench", "1"),
              Clock.systemUTC(),
              StaleLockPolicy.TAKE_OVER_PROVABLY_STALE);
      try {
        ChunkIndexStore indexes = ChunkIndexStore.of(store, options);
        SidecarChunkIndex index =
            indexes.inspect().state() == null ? indexes.create(writer) : indexes.openWriter(writer);
        return new ProductionIndex(writer, index);
      } catch (ChunkIndexException | RuntimeException e) {
        writer.close();
        throw e;
      }
    } catch (ChunkIndexException e) {
      throw e;
    } catch (StorageException e) {
      throw new ChunkIndexException(e.code(), String.valueOf(e.getMessage()), e);
    } catch (IOException e) {
      throw new ChunkIndexException(
          io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexIssueCodes.IO_FAILURE,
          "cannot open " + directory,
          e);
    }
  }

  @Override
  public ChunkIndexReader reader() throws ChunkIndexException {
    return index.reader();
  }

  @Override
  public ChunkIndexBatch begin() throws ChunkIndexException {
    return index.begin();
  }

  @Override
  public void checkpoint() throws ChunkIndexException {
    index.checkpoint();
  }

  @Override
  public void verify() throws ChunkIndexException {
    ValidationResult result = index.verify();
    if (!result.isValid()) {
      ValidationIssue first = result.issues().get(0);
      throw new ChunkIndexException(first.code(), "verification failed: " + result.issues());
    }
  }

  @Override
  public void close() {
    index.close();
    try {
      writer.close();
    } catch (StorageException ignored) {
      // Releasing the lock cannot lose committed data.
    }
  }
}
