// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.sidecar.storage.IndexFiles;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageFileSystem;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The read path shared by every reader of one index handle: verified segment handles and one LRU
 * cache, budgeted in bytes, of block headers and decoded units. A point lookup reads at most one
 * header and one unit; both are then cached. Whole-block reads (scans, checkpoints, verification)
 * bypass the cache so that a scan cannot evict the lookups' working set.
 *
 * <p>Thread-safe: segment reads are positioned, and the cache and handle table are synchronized.
 */
final class IndexData implements AutoCloseable {
  private static final int MAX_OPEN_SEGMENTS = 32;

  private final IndexFiles files;
  private final UUID indexId;
  private final long cacheBytes;
  private final Map<CacheKey, Object> cache = new LinkedHashMap<>(256, 0.75f, true);
  private long cachedBytes;
  private final Map<Long, StorageFileSystem.ReadableFile> segments =
      new LinkedHashMap<>(16, 0.75f, true);
  private boolean closed;

  IndexData(IndexFiles files, UUID indexId, long cacheBytes) {
    this.files = files;
    this.indexId = indexId;
    this.cacheBytes = cacheBytes;
  }

  /** A cache key: a block (unit -1 is its header) or one of its units. */
  private record CacheKey(long segment, long offset, int unit) {}

  static String blockScope(DimensionKey dimension, Catalog.Entry entry) {
    return IndexFormat.segmentPath(entry.segment)
        + " block "
        + dimension
        + " r."
        + entry.regionX
        + "."
        + entry.regionZ;
  }

  /** The verified header of {@code entry}'s block. */
  BlockCodec.Header header(DimensionKey dimension, Catalog.Entry entry) throws ChunkIndexException {
    CacheKey key = new CacheKey(entry.segment, entry.offset, -1);
    Object cached = cached(key);
    if (cached instanceof BlockCodec.Header header) {
      return header;
    }
    byte[] bytes = new byte[12 + entry.headerLength];
    read(entry, 0, bytes);
    BlockCodec.Header header = decodeHeader(dimension, entry, bytes);
    remember(key, header, header.retainedBytes());
    return header;
  }

  private BlockCodec.Header decodeHeader(DimensionKey dimension, Catalog.Entry entry, byte[] bytes)
      throws ChunkIndexException {
    String scope = blockScope(dimension, entry);
    BlockCodec.Header header =
        BlockCodec.readHeader(
            bytes,
            0,
            dimension,
            entry.regionX,
            entry.regionZ,
            entry.headerLength,
            entry.headerCrc,
            entry.length,
            scope);
    if (!entry.samePresence(header.presenceWords())) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.BLOCK_CORRUPT,
          "the block's presence bitmap differs from the catalog's",
          scope,
          null);
    }
    return header;
  }

  /** The record at {@code local} of {@code entry}'s region, or null if it has none. */
  @Nullable ChunkProvenance record(DimensionKey dimension, Catalog.Entry entry, int local)
      throws ChunkIndexException {
    if (!entry.present(local)) {
      return null;
    }
    BlockCodec.Header header = header(dimension, entry);
    int u = header.unitOf(local);
    CacheKey key = new CacheKey(entry.segment, entry.offset, u);
    Object cached = cached(key);
    BlockCodec.Unit unit;
    if (cached instanceof BlockCodec.Unit hit) {
      unit = hit;
    } else {
      byte[] stored = new byte[header.storedLength(u)];
      read(entry, header.unitOffset(u), stored);
      unit = BlockCodec.readUnit(header, u, stored, 0, blockScope(dimension, entry));
      remember(key, unit, unit.retainedBytes());
    }
    return unit.record(local);
  }

  /** The whole block of {@code entry}, verified, for streaming every unit in order. */
  WholeBlock block(DimensionKey dimension, Catalog.Entry entry) throws ChunkIndexException {
    byte[] bytes = new byte[entry.length];
    read(entry, 0, bytes);
    return new WholeBlock(
        decodeHeader(dimension, entry, bytes), bytes, blockScope(dimension, entry));
  }

  /** A block read in full. Units are verified and inflated one at a time, on demand. */
  static final class WholeBlock {
    final BlockCodec.Header header;
    private final byte[] bytes;
    private final String scope;

    WholeBlock(BlockCodec.Header header, byte[] bytes, String scope) {
      this.header = header;
      this.bytes = bytes;
      this.scope = scope;
    }

    BlockCodec.Unit unit(int u) throws ChunkIndexException {
      return BlockCodec.readUnit(header, u, bytes, header.unitOffset(u), scope);
    }

    /** Every record, by local index (decoded now). */
    @Nullable ChunkProvenance[] records() throws ChunkIndexException {
      @Nullable ChunkProvenance[] out = new ChunkProvenance[1024];
      int perUnit = header.rowsPerUnit() * 32;
      for (int u = 0; u < header.units(); u++) {
        BlockCodec.Unit unit = unit(u);
        for (int i = u * perUnit; i < (u + 1) * perUnit; i++) {
          out[i] = unit.record(i);
        }
      }
      return out;
    }

    /** The block's exact bytes (compaction copies them unchanged). */
    byte[] bytes() {
      return bytes;
    }
  }

  // ---- segments ------------------------------------------------------------------------------

  private void read(Catalog.Entry entry, int within, byte[] target) throws ChunkIndexException {
    StorageFileSystem.ReadableFile file = segment(entry.segment);
    try {
      file.readFully(entry.offset + within, target, 0, target.length);
    } catch (EOFException e) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.SEGMENT_MISSING,
          "the segment ends before a block the catalog references",
          IndexFormat.segmentPath(entry.segment).toString(),
          e);
    } catch (IOException e) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.IO_FAILURE,
          "cannot read a block",
          IndexFormat.segmentPath(entry.segment).toString(),
          e);
    }
  }

  private StorageFileSystem.ReadableFile segment(long number) throws ChunkIndexException {
    synchronized (segments) {
      requireOpen();
      StorageFileSystem.ReadableFile open = segments.get(number);
      if (open != null) {
        return open;
      }
      SidecarPath path = IndexFormat.segmentPath(number);
      StorageFileSystem.ReadableFile file;
      try {
        file = files.openRandomAccess(path);
      } catch (NoSuchFileException e) {
        throw new ChunkIndexException(
            ChunkIndexIssueCodes.SEGMENT_MISSING,
            "a referenced segment is missing",
            path.toString(),
            e);
      } catch (StorageException e) {
        throw new ChunkIndexException(
            e.code(), "cannot open a segment: " + e.getMessage(), path.toString(), e);
      } catch (IOException e) {
        throw new ChunkIndexException(
            ChunkIndexIssueCodes.IO_FAILURE, "cannot open a segment", path.toString(), e);
      }
      try {
        verifySegmentHeader(file, number, path.toString());
      } catch (ChunkIndexException e) {
        closeQuietly(file);
        throw e;
      }
      segments.put(number, file);
      if (segments.size() > MAX_OPEN_SEGMENTS) {
        Iterator<StorageFileSystem.ReadableFile> eldest = segments.values().iterator();
        closeQuietly(eldest.next());
        eldest.remove();
      }
      return file;
    }
  }

  private void verifySegmentHeader(StorageFileSystem.ReadableFile file, long number, String scope)
      throws ChunkIndexException {
    byte[] head = new byte[IndexFormat.HEADER_BYTES];
    try {
      file.readFully(0, head, 0, head.length);
    } catch (IOException e) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.HEADER_CORRUPT, "segment header unreadable", scope, e);
    }
    IndexFormat.FileHeader header =
        IndexFormat.readHeader(head, 0, head.length, IndexFormat.SEGMENT_MAGIC, scope);
    IndexFormat.requireIndex(header, indexId, scope);
    if (header.number() != number) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.HEADER_CORRUPT,
          "segment header names segment " + header.number(),
          scope,
          null);
    }
  }

  /** Closes the handle of a segment about to be deleted, and drops its cached blocks. */
  void retire(long number) {
    synchronized (segments) {
      StorageFileSystem.ReadableFile open = segments.remove(number);
      if (open != null) {
        closeQuietly(open);
      }
    }
    synchronized (cache) {
      Iterator<Map.Entry<CacheKey, Object>> it = cache.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<CacheKey, Object> e = it.next();
        if (e.getKey().segment() == number) {
          cachedBytes -= size(e.getValue());
          it.remove();
        }
      }
    }
  }

  // ---- cache ---------------------------------------------------------------------------------

  private @Nullable Object cached(CacheKey key) {
    synchronized (cache) {
      return cache.get(key);
    }
  }

  private void remember(CacheKey key, Object value, long bytes) {
    synchronized (cache) {
      Object previous = cache.put(key, value);
      cachedBytes += bytes - (previous == null ? 0 : size(previous));
      Iterator<Object> eldest = cache.values().iterator();
      while (cachedBytes > cacheBytes && eldest.hasNext()) {
        cachedBytes -= size(eldest.next());
        eldest.remove();
      }
    }
  }

  private static long size(Object value) {
    return value instanceof BlockCodec.Header h
        ? h.retainedBytes()
        : ((BlockCodec.Unit) value).retainedBytes();
  }

  /** Bytes currently held by the cache; never more than the budget after an insertion. */
  long cachedBytes() {
    synchronized (cache) {
      return cachedBytes;
    }
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("The index is closed");
    }
  }

  @Override
  public void close() {
    synchronized (segments) {
      closed = true;
      List<StorageFileSystem.ReadableFile> open = new ArrayList<>(segments.values());
      segments.clear();
      open.forEach(IndexData::closeQuietly);
    }
    synchronized (cache) {
      cache.clear();
      cachedBytes = 0;
    }
  }

  private static void closeQuietly(StorageFileSystem.ReadableFile file) {
    try {
      file.close();
    } catch (IOException ignored) {
      // A read-only handle has nothing to lose.
    }
  }
}
