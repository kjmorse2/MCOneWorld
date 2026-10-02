// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.storage.IndexFileWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The chunk index of one sidecar, stored in its reserved {@code chunk-index/} area (ADR 0007,
 * {@code docs/formats/chunk-index-v1.md}).
 *
 * <ul>
 *   <li>Without the lock, and never writing: {@link #inspect()}, {@link #verify()}, and {@link
 *       #openReader()}.
 *   <li>With the sidecar writer lock: {@link #create} (explicit; nothing is ever created
 *       implicitly) and {@link #openWriter}.
 * </ul>
 */
public final class ChunkIndexStore {
  private final SidecarStore store;
  private final ChunkIndexOptions options;

  private ChunkIndexStore(SidecarStore store, ChunkIndexOptions options) {
    this.store = Objects.requireNonNull(store, "store");
    this.options = Objects.requireNonNull(options, "options");
  }

  /** The index of {@code store}, with default options. */
  public static ChunkIndexStore of(SidecarStore store) {
    return new ChunkIndexStore(store, ChunkIndexOptions.DEFAULT);
  }

  /** The index of {@code store}. */
  public static ChunkIndexStore of(SidecarStore store, ChunkIndexOptions options) {
    return new ChunkIndexStore(store, options);
  }

  /**
   * Reports the committed state and explains every file of the area, without reading blocks.
   * Read-only; never throws for a damaged or missing index, which it reports as findings.
   */
  public ChunkIndexStatus inspect() {
    try {
      IndexSnapshot snapshot = IndexLoader.load(store.indexFiles(), options);
      return new ChunkIndexStatus(
          snapshot.state(), IndexInspector.inspectFiles(store.indexFiles(), snapshot));
    } catch (ChunkIndexException e) {
      return new ChunkIndexStatus(
          null, ValidationResult.of(List.of(IndexInspector.issue(e, location(e)))));
    }
  }

  /**
   * Inspects, then decodes and checks every block and logged mutation. Read-only.
   *
   * @throws ChunkIndexException if the index cannot even be loaded (no index, corrupt catalog)
   */
  public ValidationResult verify() throws ChunkIndexException {
    IndexSnapshot snapshot = IndexLoader.load(store.indexFiles(), options);
    try (IndexData data =
        new IndexData(store.indexFiles(), snapshot.catalog().indexId, options.cacheBytes())) {
      return IndexInspector.verify(store.indexFiles(), data, snapshot);
    }
  }

  /**
   * Opens a read-only snapshot of the committed state, without the lock. It works while another
   * process writes; see {@link SidecarChunkIndex} for what it may then see. The caller closes it.
   */
  public ChunkIndexSnapshot openReader() throws ChunkIndexException {
    IndexSnapshot snapshot = IndexLoader.load(store.indexFiles(), options);
    IndexData data =
        new IndexData(store.indexFiles(), snapshot.catalog().indexId, options.cacheBytes());
    return new SnapshotReader(
        new SnapshotReader.Owner() {
          private boolean closed;

          @Override
          public void requireOpen() {
            if (closed) {
              throw new IllegalStateException("The reader is closed");
            }
          }

          @Override
          public void released(IndexSnapshot released) {
            closed = true;
            data.close();
          }
        },
        data,
        snapshot);
  }

  /**
   * Creates a new, empty index. {@code chunk-index/} must not exist or must be empty.
   *
   * @throws ChunkIndexException {@code INDEX_EXISTS}, or {@code LOCK_LOST}
   * @throws IllegalStateException if this writer already has an index open
   */
  public SidecarChunkIndex create(SidecarWriter writer, UUID indexId) throws ChunkIndexException {
    IndexFileWriter claim = claim(writer);
    try {
      return SidecarChunkIndex.create(claim, indexId, options);
    } catch (ChunkIndexException | RuntimeException e) {
      claim.close();
      throw e;
    }
  }

  /** Creates a new, empty index with a random id; see {@link #create(SidecarWriter, UUID)}. */
  public SidecarChunkIndex create(SidecarWriter writer) throws ChunkIndexException {
    return create(writer, UUID.randomUUID());
  }

  /**
   * Opens the existing index for writing.
   *
   * @throws ChunkIndexException {@code INDEX_MISSING}, {@code READ_ONLY} (newer minor version),
   *     {@code UNEXPLAINED_FILE}, a corruption code, or {@code LOCK_LOST}
   * @throws IllegalStateException if this writer already has an index open
   */
  public SidecarChunkIndex openWriter(SidecarWriter writer) throws ChunkIndexException {
    IndexFileWriter claim = claim(writer);
    try {
      return SidecarChunkIndex.open(claim, options);
    } catch (ChunkIndexException | RuntimeException e) {
      claim.close();
      throw e;
    }
  }

  private static IndexFileWriter claim(SidecarWriter writer) throws ChunkIndexException {
    try {
      return writer.openIndexWriter();
    } catch (StorageException e) {
      throw new ChunkIndexException(e.code(), String.valueOf(e.getMessage()), e);
    }
  }

  private static IssueLocation location(ChunkIndexException e) {
    String scope = e.scope();
    return scope == null ? IssueLocation.root() : IssueLocation.root().field("files").key(scope);
  }
}
