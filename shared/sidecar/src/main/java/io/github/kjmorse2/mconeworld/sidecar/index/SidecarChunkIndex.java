// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.storage.IndexFileWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageFileSystem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The writable chunk index of one sidecar ({@code docs/formats/chunk-index-v1.md}, ADR 0007).
 * Obtain it from {@link ChunkIndexStore#openWriter} or {@link ChunkIndexStore#create}, which
 * require the sidecar writer lock.
 *
 * <h2>Writes</h2>
 *
 * <ul>
 *   <li><b>Commit.</b> A batch validates each mutation by whether its chunk has a record, which the
 *       catalog's presence bitmaps and the logged mutations answer without reading any block. It
 *       appends the mutations as one CRC-framed delta to the log and calls {@code fdatasync}. A
 *       commit never reads a record, so its cost depends on the batch, not on the index.
 *   <li><b>Checkpoint.</b> Folds every logged chunk into new region blocks appended to the current
 *       segment, syncs it, and atomically replaces the catalog (the commit point). It runs when
 *       {@link #checkpoint()} is called, and inside a commit once the log holds {@code
 *       checkpointMutations} mutations, which bounds memory. A log that ends in a torn frame is
 *       never appended to: the first commit checkpoints first.
 *   <li><b>Failure.</b> If a write fails, it may or may not have reached the disk, so this handle
 *       no longer knows the durable state. It becomes <em>broken</em>: commits and checkpoints fail
 *       with {@code HANDLE_BROKEN} until it is closed and reopened. Reopening reads exactly what is
 *       on disk, which is the last committed state or the one being committed.
 * </ul>
 *
 * <h2>Concurrency</h2>
 *
 * <ul>
 *   <li>One writer per sidecar (the sidecar lock), one handle per writer ({@link IndexFileWriter}),
 *       and one open batch per handle.
 *   <li>Commits, checkpoints, and maintenance are serialized internally; readers never wait for
 *       them.
 *   <li>{@link #reader()} may be called from any thread. A reader is a snapshot of the state
 *       committed when it opened and is used by one thread at a time. Checkpoints do not invalidate
 *       readers: blocks are never modified, and segments an open reader may need are never deleted.
 *   <li>Closing the handle invalidates every reader, cursor, and batch.
 *   <li>Readers in other processes ({@link ChunkIndexStore#openReader()}) see a consistent
 *       committed state. If explicit maintenance deletes a segment their older snapshot needs, they
 *       fail with {@code SEGMENT_MISSING} and should reopen.
 * </ul>
 */
public final class SidecarChunkIndex implements ChunkIndex {
  private final IndexFileWriter files;
  private final ChunkIndexOptions options;
  private final IndexData data;
  private final Object writeLock = new Object();
  private final Map<IndexSnapshot, Integer> openSnapshots = new HashMap<>();
  private volatile IndexSnapshot current;
  private volatile boolean closed;
  private boolean broken;
  private @Nullable Batch openBatch;

  private StorageFileSystem.@Nullable WritableFile log;
  private long logSize;
  private StorageFileSystem.@Nullable WritableFile segment;
  private long segmentNumber = -1;
  private long segmentSize;

  private SidecarChunkIndex(
      IndexFileWriter files, ChunkIndexOptions options, IndexSnapshot snapshot) {
    this.files = files;
    this.options = options;
    this.current = snapshot;
    this.data = new IndexData(files.files(), snapshot.catalog().indexId, options.cacheBytes());
  }

  /** Creates the area and an empty index (generation 0). The caller owns the claim. */
  static SidecarChunkIndex create(IndexFileWriter files, UUID indexId, ChunkIndexOptions options)
      throws ChunkIndexException {
    try {
      if (files.files().areaExists()) {
        List<String> entries = files.files().list(null);
        boolean onlyEmptySegments =
            entries.isEmpty()
                || (entries.equals(List.of(IndexFormat.SEGMENTS_DIR))
                    && files.files().list(SidecarPath.of(IndexFormat.SEGMENTS_DIR)).isEmpty());
        if (!onlyEmptySegments) {
          throw new ChunkIndexException(
              ChunkIndexIssueCodes.INDEX_EXISTS,
              "chunk-index/ is not empty; open the existing index instead",
              "chunk-index",
              null);
        }
      } else {
        files.createArea();
      }
      if (!files.files().exists(SidecarPath.of(IndexFormat.SEGMENTS_DIR))) {
        files.createDirectory(SidecarPath.of(IndexFormat.SEGMENTS_DIR));
      }
      Catalog empty = Catalog.empty(indexId);
      byte[] bytes = empty.encode();
      files.replaceAtomically(IndexFormat.catalogTemp(0), IndexFormat.CATALOG, bytes);
      return new SidecarChunkIndex(
          files,
          options,
          new IndexSnapshot(
              empty,
              Overlay.EMPTY,
              IndexFormat.HEADER_BYTES,
              false,
              false,
              false,
              IndexFormat.crc(bytes, 0, bytes.length - 4),
              0));
    } catch (ChunkIndexException e) {
      throw e;
    } catch (IOException e) {
      throw failure("cannot create the index", e);
    }
  }

  /** Opens the existing index for writing. The caller owns the claim. */
  static SidecarChunkIndex open(IndexFileWriter files, ChunkIndexOptions options)
      throws ChunkIndexException {
    IndexSnapshot snapshot = IndexLoader.load(files.files(), options);
    if (snapshot.readOnly()) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.READ_ONLY,
          "a newer minor format version wrote this index; this version may read it but not write",
          "chunk-index",
          null);
    }
    ValidationResult findings = IndexInspector.inspectFiles(files.files(), snapshot);
    if (!findings.isValid()) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.UNEXPLAINED_FILE,
          "chunk-index/ holds files the format cannot explain; inspect and resolve them: "
              + findings.issues(),
          "chunk-index",
          null);
    }
    return new SidecarChunkIndex(files, options, snapshot);
  }

  /** The committed state that new readers see. */
  public ChunkIndexState state() {
    requireOpen();
    return current.state();
  }

  // ---- reading -------------------------------------------------------------------------------

  @Override
  public ChunkIndexSnapshot reader() {
    requireOpen();
    synchronized (openSnapshots) {
      IndexSnapshot snapshot = current;
      openSnapshots.merge(snapshot, 1, Integer::sum);
      return new SnapshotReader(owner, data, snapshot);
    }
  }

  private final SnapshotReader.Owner owner =
      new SnapshotReader.Owner() {
        @Override
        public void requireOpen() {
          SidecarChunkIndex.this.requireOpen();
        }

        @Override
        public void released(IndexSnapshot snapshot) {
          synchronized (openSnapshots) {
            openSnapshots.computeIfPresent(snapshot, (s, n) -> n == 1 ? null : n - 1);
          }
        }
      };

  /**
   * Runs the deepest verification: every block decoded and checked against its catalog entry and
   * summary, every logged mutation resolved, and every file in the area explained. Read-only.
   */
  public ValidationResult verify() throws ChunkIndexException {
    requireOpen();
    return IndexInspector.verify(files.files(), data, current);
  }

  // ---- writing -------------------------------------------------------------------------------

  @Override
  public synchronized ChunkIndexBatch begin() {
    requireOpen();
    if (openBatch != null) {
      throw new IllegalStateException("Another batch is open; an index has one writer at a time");
    }
    Batch batch = new Batch();
    openBatch = batch;
    return batch;
  }

  /** Mutations logged since the last checkpoint; a scheduler may checkpoint when it grows. */
  public int loggedMutations() {
    return current.overlay().mutations();
  }

  /**
   * Folds the log into region blocks now. Does nothing if nothing is logged and the log is not
   * torn.
   *
   * @throws ChunkIndexException on failure, after which the handle is broken
   */
  public void checkpoint() throws ChunkIndexException {
    synchronized (writeLock) {
      requireWritable();
      IndexSnapshot snapshot = current;
      if (snapshot.overlay().isEmpty() && !snapshot.tornTail()) {
        return;
      }
      checkpointLocked(snapshot, false);
    }
  }

  /**
   * Checkpoints, and also copies the live blocks of every segment that is less than half live into
   * the current segment, so that those segments become unreferenced. Their files stay until {@link
   * #removeObsoleteFiles()} is called.
   */
  public void compact() throws ChunkIndexException {
    synchronized (writeLock) {
      requireWritable();
      checkpointLocked(current, true);
    }
  }

  /**
   * Explicit maintenance: deletes segments that neither the current catalog nor any open reader
   * references, logs of older generations, and the temporary catalog of an interrupted checkpoint.
   * Every one of them is provably unused; nothing else is ever deleted. A failure here never
   * touches committed state, so it leaves the handle usable: call it again.
   *
   * @return the deleted paths, relative to {@code chunk-index/}
   */
  public List<String> removeObsoleteFiles() throws ChunkIndexException {
    synchronized (writeLock) {
      requireWritable();
      Set<Long> referenced = new HashSet<>();
      collectSegments(current.catalog(), referenced);
      synchronized (openSnapshots) {
        for (IndexSnapshot s : openSnapshots.keySet()) {
          collectSegments(s.catalog(), referenced);
        }
      }
      if (segmentNumber >= 0) {
        referenced.add(segmentNumber);
      }
      long generation = current.catalog().generation;
      List<String> deleted = new ArrayList<>();
      try {
        for (String name : files.files().list(SidecarPath.of(IndexFormat.SEGMENTS_DIR))) {
          long number = IndexFormat.segmentNumber(name);
          if (number >= 1 && !referenced.contains(number)) {
            data.retire(number);
            files.delete(IndexFormat.segmentPath(number));
            deleted.add(IndexFormat.segmentPath(number).toString());
          }
        }
        for (String name : files.files().list(null)) {
          long logGeneration = IndexFormat.logGeneration(name);
          long tempGeneration = IndexFormat.catalogTempGeneration(name);
          if ((logGeneration >= 0 && logGeneration < generation)
              || tempGeneration == generation + 1) {
            files.delete(SidecarPath.of(name));
            deleted.add(name);
          }
        }
      } catch (IOException e) {
        throw failure("maintenance failed", e);
      }
      return List.copyOf(deleted);
    }
  }

  private static void collectSegments(Catalog catalog, Set<Long> into) {
    for (NavigableMap<Long, Catalog.Entry> map : catalog.regions.values()) {
      for (Catalog.Entry e : map.values()) {
        into.add(e.segment);
      }
    }
  }

  private void commitMutations(Map<ChunkPosition, List<ChunkMutation>> mutations)
      throws ChunkIndexException {
    synchronized (writeLock) {
      requireWritable();
      if (mutations.isEmpty()) {
        return;
      }
      IndexSnapshot snapshot = current;
      if (snapshot.tornTail()) {
        checkpointLocked(snapshot, false);
        snapshot = current;
      }
      byte[] frame = DeltaLog.frame(mutations);
      try {
        StorageFileSystem.WritableFile out = openLog(snapshot);
        out.write(frame, 0, frame.length);
        out.syncData();
      } catch (IOException e) {
        broken = true;
        throw failure(
            "the commit could not be made durable; its outcome is unknown until reopen", e);
      }
      logSize += frame.length;
      int payloadCrc =
          new ByteSource(frame, frame.length - 4, 4, ChunkIndexIssueCodes.LOG_CORRUPT, "frame")
              .i32();
      current =
          new IndexSnapshot(
              snapshot.catalog(),
              snapshot.overlay().plus(mutations),
              logSize,
              false,
              true,
              false,
              snapshot.catalogCrc(),
              payloadCrc);
      if (current.overlay().mutations() >= options.checkpointMutations()) {
        checkpointLocked(current, false);
      }
    }
  }

  /** The appender for the current generation's log, creating the file if needed. */
  private StorageFileSystem.WritableFile openLog(IndexSnapshot snapshot) throws IOException {
    StorageFileSystem.WritableFile out = log;
    if (out != null) {
      return out;
    }
    files.verifyLockHeld();
    SidecarPath path = IndexFormat.logPath(snapshot.catalog().generation);
    if (snapshot.logExists()) {
      out = files.openAppend(path, snapshot.logEnd());
      logSize = snapshot.logEnd();
    } else {
      byte[] header = DeltaLog.header(snapshot.catalog().indexId, snapshot.catalog().generation);
      out = files.createNew(path);
      try {
        out.write(header, 0, header.length);
        out.sync();
        files.syncDirectoryOf(path);
      } catch (IOException e) {
        closeQuietly(out);
        throw e;
      }
      logSize = header.length;
    }
    log = out;
    return out;
  }

  private void checkpointLocked(IndexSnapshot snapshot, boolean relocate)
      throws ChunkIndexException {
    try {
      files.verifyLockHeld();
      Catalog base = snapshot.catalog();
      Map<DimensionKey, NavigableMap<Long, Catalog.Entry>> regions = new TreeMap<>(base.regions);
      boolean segmentCreated = false;
      for (Map.Entry<DimensionKey, NavigableMap<Long, List<ChunkMutation>>> dimension :
          snapshot.overlay().dimensions().entrySet()) {
        DimensionKey key = dimension.getKey();
        TreeMap<Long, Catalog.Entry> map =
            new TreeMap<>(base.regions.getOrDefault(key, new TreeMap<>()));
        TreeMap<Long, List<Map.Entry<Long, List<ChunkMutation>>>> byRegion = new TreeMap<>();
        for (Map.Entry<Long, List<ChunkMutation>> logged : dimension.getValue().entrySet()) {
          long chunkKey = logged.getKey();
          byRegion
              .computeIfAbsent(
                  ChunkOrder.regionStart(
                      ChunkOrder.regionX(chunkKey), ChunkOrder.regionZ(chunkKey)),
                  r -> new ArrayList<>())
              .add(logged);
        }
        for (Map.Entry<Long, List<Map.Entry<Long, List<ChunkMutation>>>> region :
            byRegion.entrySet()) {
          Catalog.Entry old = map.get(region.getKey());
          @Nullable ChunkProvenance[] records =
              old == null ? new ChunkProvenance[1024] : data.block(key, old).records();
          for (Map.Entry<Long, List<ChunkMutation>> logged : region.getValue()) {
            int local = ChunkOrder.localIndex(logged.getKey());
            records[local] = Overlay.resolve(records[local], logged.getValue());
          }
          int rx = ChunkOrder.regionX(region.getKey());
          int rz = ChunkOrder.regionZ(region.getKey());
          boolean any = false;
          for (ChunkProvenance r : records) {
            any |= r != null;
          }
          if (!any) {
            map.remove(region.getKey());
            continue;
          }
          BlockCodec.Encoded block = BlockCodec.encode(key, rx, rz, records, options.rowsPerUnit());
          segmentCreated |= ensureSegment(base);
          long offset = append(block.bytes());
          map.put(region.getKey(), Catalog.Entry.of(rx, rz, segmentNumber, offset, block, records));
        }
        if (map.isEmpty()) {
          regions.remove(key);
        } else {
          regions.put(key, map);
        }
      }
      if (relocate) {
        segmentCreated |= relocateSparseSegments(base, regions);
      }
      StorageFileSystem.WritableFile out = segment;
      if (out != null) {
        out.sync();
        if (segmentCreated) {
          files.syncDirectoryOf(IndexFormat.segmentPath(segmentNumber));
        }
      }
      long nextSegment = Math.max(base.nextSegment, segmentNumber + 1);
      Catalog next = new Catalog(base.indexId, base.generation + 1, nextSegment, false, regions);
      byte[] bytes = next.encode();
      SidecarPath temp = IndexFormat.catalogTemp(next.generation);
      if (files.files().exists(temp)) {
        // Provably an interrupted checkpoint of this very generation: never committed.
        files.delete(temp);
      }
      files.replaceAtomically(temp, IndexFormat.CATALOG, bytes); // the commit point
      StorageFileSystem.WritableFile oldLog = log;
      log = null;
      current =
          new IndexSnapshot(
              next,
              Overlay.EMPTY,
              IndexFormat.HEADER_BYTES,
              false,
              false,
              false,
              IndexFormat.crc(bytes, 0, bytes.length - 4),
              0);
      if (oldLog != null) {
        closeQuietly(oldLog);
      }
      deleteSupersededLog(base.generation);
    } catch (ChunkIndexException e) {
      broken = true;
      throw e;
    } catch (IOException e) {
      broken = true;
      throw failure("the checkpoint failed; its outcome is unknown until reopen", e);
    }
  }

  /** The superseded log holds only folded commits; removing it is part of the checkpoint. */
  private void deleteSupersededLog(long generation) {
    SidecarPath path = IndexFormat.logPath(generation);
    try {
      if (files.files().exists(path)) {
        files.delete(path);
      }
    } catch (IOException ignored) {
      // A stale log is reported by inspection and ignored by readers.
    }
  }

  /** Copies the live blocks of mostly-dead segments forward; returns whether a segment started. */
  private boolean relocateSparseSegments(
      Catalog base, Map<DimensionKey, NavigableMap<Long, Catalog.Entry>> regions)
      throws IOException, ChunkIndexException {
    Map<Long, Long> live = new HashMap<>();
    for (NavigableMap<Long, Catalog.Entry> map : regions.values()) {
      for (Catalog.Entry e : map.values()) {
        live.merge(e.segment, (long) e.length, Long::sum);
      }
    }
    Set<Long> sparse = new HashSet<>();
    for (Map.Entry<Long, Long> segmentLive : live.entrySet()) {
      long number = segmentLive.getKey();
      if (number == segmentNumber) {
        continue;
      }
      long size = files.files().size(IndexFormat.segmentPath(number));
      if (segmentLive.getValue() * 2 < size) {
        sparse.add(number);
      }
    }
    boolean created = false;
    for (Map.Entry<DimensionKey, NavigableMap<Long, Catalog.Entry>> dimension :
        regions.entrySet()) {
      TreeMap<Long, Catalog.Entry> map = new TreeMap<>(dimension.getValue());
      for (Map.Entry<Long, Catalog.Entry> region : map.entrySet()) {
        Catalog.Entry e = region.getValue();
        if (sparse.contains(e.segment)) {
          byte[] bytes = data.block(dimension.getKey(), e).bytes();
          created |= ensureSegment(base);
          region.setValue(e.movedTo(segmentNumber, append(bytes)));
        }
      }
      dimension.setValue(map);
    }
    return created;
  }

  /** Opens a new segment if none is open or the current one is full; returns whether it did. */
  private boolean ensureSegment(Catalog base) throws IOException {
    StorageFileSystem.WritableFile out = segment;
    if (out != null && segmentSize < options.segmentRollBytes()) {
      return false;
    }
    if (out != null) {
      out.sync();
      closeQuietly(out);
      segment = null;
    }
    long number = Math.max(base.nextSegment, segmentNumber + 1);
    while (files.files().exists(IndexFormat.segmentPath(number))) {
      number++; // an orphan from an interrupted checkpoint: never reused, never deleted here
    }
    StorageFileSystem.WritableFile created = files.createNew(IndexFormat.segmentPath(number));
    ByteSink header = new ByteSink(IndexFormat.HEADER_BYTES);
    IndexFormat.writeHeader(header, IndexFormat.SEGMENT_MAGIC, base.indexId, number);
    try {
      created.write(header.array(), 0, header.size());
    } catch (IOException e) {
      closeQuietly(created);
      throw e;
    }
    segment = created;
    segmentNumber = number;
    segmentSize = header.size();
    return true;
  }

  private long append(byte[] bytes) throws IOException {
    StorageFileSystem.WritableFile out = segment;
    if (out == null) {
      throw new IllegalStateException("no segment is open");
    }
    long offset = segmentSize;
    out.write(bytes, 0, bytes.length);
    segmentSize += bytes.length;
    return offset;
  }

  // ---- lifecycle -----------------------------------------------------------------------------

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("The index is closed");
    }
  }

  private void requireWritable() throws ChunkIndexException {
    requireOpen();
    if (broken) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.HANDLE_BROKEN,
          "an earlier write failed; close this handle and reopen the index",
          "chunk-index",
          null);
    }
  }

  @Override
  public void close() {
    synchronized (writeLock) {
      if (closed) {
        return;
      }
      closed = true;
      openBatch = null;
      StorageFileSystem.WritableFile out = log;
      if (out != null) {
        closeQuietly(out);
      }
      StorageFileSystem.WritableFile seg = segment;
      if (seg != null) {
        closeQuietly(seg);
      }
      data.close();
      files.close();
    }
  }

  private static void closeQuietly(StorageFileSystem.WritableFile file) {
    try {
      file.close();
    } catch (IOException ignored) {
      // Everything acknowledged was synced first.
    }
  }

  private static ChunkIndexException failure(String message, IOException e) {
    if (e instanceof ChunkIndexException c) {
      return c;
    }
    if (e instanceof StorageException s) {
      return new ChunkIndexException(s.code(), message + ": " + s.getMessage(), "chunk-index", s);
    }
    return new ChunkIndexException(ChunkIndexIssueCodes.IO_FAILURE, message, "chunk-index", e);
  }

  /** A deferred batch: validated by existence, committed as one log frame. */
  private final class Batch implements ChunkIndexBatch {
    private final Map<ChunkPosition, List<ChunkMutation>> pending = new LinkedHashMap<>();
    private final Map<ChunkPosition, Boolean> exists = new HashMap<>();
    private boolean done;
    private boolean poisoned;

    @Override
    public void apply(ChunkMutation mutation) throws ChunkIndexException {
      requireUsable();
      ChunkPosition chunk = mutation.chunk();
      Boolean known = exists.get(chunk);
      boolean before = known != null ? known : committedExists(chunk);
      if (!before
          && (mutation instanceof ChunkMutation.RecordActivity
              || mutation instanceof ChunkMutation.SetProtection)) {
        poisoned = true;
        mutation.applyTo(null); // throws the contract's own record_absent rejection
      }
      pending.computeIfAbsent(chunk, c -> new ArrayList<>()).add(mutation);
      exists.put(chunk, Overlay.existsAfter(mutation, before));
    }

    private boolean committedExists(ChunkPosition chunk) {
      IndexSnapshot snapshot = current;
      boolean present = snapshot.catalog().present(chunk.dimension(), ChunkOrder.key(chunk));
      List<ChunkMutation> logged = snapshot.overlay().get(chunk);
      if (logged != null) {
        for (ChunkMutation m : logged) {
          present = Overlay.existsAfter(m, present);
        }
      }
      return present;
    }

    @Override
    public void commit() throws ChunkIndexException {
      requireUsable();
      commitMutations(pending);
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
      exists.clear();
      synchronized (SidecarChunkIndex.this) {
        if (openBatch == this) {
          openBatch = null;
        }
      }
    }

    private void requireUsable() {
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
