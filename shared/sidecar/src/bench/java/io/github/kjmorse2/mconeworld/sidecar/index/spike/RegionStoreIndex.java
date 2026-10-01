// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkEnvelope;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkCursor;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery;
import io.github.kjmorse2.mconeworld.sidecar.index.ProtectionFilter;
import io.github.kjmorse2.mconeworld.sidecar.index.SeasonFilter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32C;
import org.jspecify.annotations.Nullable;

/**
 * C1: region-partitioned record blocks ({@link RecordCodec}) located by a {@link Catalog}.
 *
 * <p>Copy-on-write: a commit writes new blocks for the regions it touches, syncs them, then
 * atomically replaces {@code catalog} (write {@code catalog.tmp}, sync, rename, sync the
 * directory). Blocks are never modified in place, so a reader keeps a consistent snapshot simply by
 * keeping its catalog. Superseded files are deleted only once no open reader can still see them.
 *
 * <p>Two block layouts are measured:
 *
 * <ul>
 *   <li>{@link Layout#FILE_PER_REGION}: one immutable file per block version. Simple, but a commit
 *       syncs one file per touched region.
 *   <li>{@link Layout#SEGMENTS}: blocks appended to segment files that roll at 64 MiB. A commit
 *       syncs one segment and the catalog, however many regions it touches. Mostly-dead segments
 *       are compacted by copying their live blocks forward.
 * </ul>
 */
public class RegionStoreIndex extends SpikeIndexBase {
  /** How region blocks are laid out in files. */
  public enum Layout {
    FILE_PER_REGION,
    SEGMENTS
  }

  static final long SEGMENT_ROLL_BYTES = 64L << 20;

  /** Heap budget for cached blocks (inflated bytes plus offsets), sized for a 256 MiB heap. */
  private static final long CACHE_BYTES = 48L << 20;

  private static final int OPEN_CHANNELS = 64;

  final Path directory;
  private final Path data;
  private final Layout layout;
  private volatile Catalog catalog;

  private final Map<Long, RecordCodec.Block> blockCache = new LinkedHashMap<>(256, 0.75f, true);
  private long cachedBytes;
  private final Map<Long, FileChannel> channels = new LinkedHashMap<>(64, 0.75f, true);

  private final TreeMap<Long, Integer> readersByGeneration = new TreeMap<>();
  private final List<long[]> pendingDeletes = new ArrayList<>(); // {supersededAtGeneration, fileId}

  private long currentSegment = -1;
  private long currentSegmentSize;
  private @Nullable FileChannel segmentWriter;

  RegionStoreIndex(Path directory, Layout layout) throws ChunkIndexException {
    this.directory = directory;
    this.data = directory.resolve("data");
    this.layout = layout;
    try {
      Files.createDirectories(data);
      Path file = directory.resolve("catalog");
      catalog = Files.exists(file) ? Catalog.decode(Files.readAllBytes(file)) : Catalog.empty();
    } catch (IOException e) {
      throw SpikeCodes.io("cannot open " + directory, e);
    }
  }

  /** Opens (or creates) a C1 index in {@code directory}. */
  public static RegionStoreIndex open(Path directory, Layout layout) throws ChunkIndexException {
    return new RegionStoreIndex(directory, layout);
  }

  // ---- reading -------------------------------------------------------------------------------

  @Override
  public ChunkIndexReader reader() {
    requireOpen();
    synchronized (this) {
      Catalog snapshot = catalog;
      readersByGeneration.merge(snapshot.generation, 1, Integer::sum);
      return newReader(snapshot);
    }
  }

  /** The committed catalog. */
  final Catalog committedCatalog() {
    return catalog;
  }

  /** Creates the reader for {@code snapshot}; C3 overrides it to add bitmaps. */
  ChunkIndexReader newReader(Catalog snapshot) {
    return new Reader(snapshot);
  }

  final synchronized void readerClosed(long generation) {
    readersByGeneration.computeIfPresent(generation, (g, n) -> n == 1 ? null : n - 1);
    collectGarbage();
  }

  final @Nullable ChunkProvenance find(Catalog snapshot, ChunkPosition chunk)
      throws ChunkIndexException {
    NavigableMap<Long, Catalog.Entry> map = snapshot.regions.get(chunk.dimension());
    if (map == null) {
      return null;
    }
    long key = ChunkOrder.key(chunk);
    Catalog.Entry entry =
        map.get(ChunkOrder.regionStart(ChunkOrder.regionX(key), ChunkOrder.regionZ(key)));
    if (entry == null || !entry.present(ChunkOrder.localIndex(key))) {
      return null;
    }
    return block(chunk.dimension(), entry).at(ChunkOrder.localIndex(key));
  }

  /** Whether {@code snapshot} has a record for {@code chunk}, from its presence bitmap alone. */
  static boolean present(Catalog snapshot, ChunkPosition chunk) {
    NavigableMap<Long, Catalog.Entry> map = snapshot.regions.get(chunk.dimension());
    if (map == null) {
      return false;
    }
    long key = ChunkOrder.key(chunk);
    Catalog.Entry entry =
        map.get(ChunkOrder.regionStart(ChunkOrder.regionX(key), ChunkOrder.regionZ(key)));
    return entry != null && entry.present(ChunkOrder.localIndex(key));
  }

  final RecordCodec.Block block(DimensionKey dimension, Catalog.Entry entry)
      throws ChunkIndexException {
    long cacheKey = entry.fileId * 0x9E3779B97F4A7C15L ^ entry.offset;
    synchronized (blockCache) {
      RecordCodec.Block cached = blockCache.get(cacheKey);
      if (cached != null
          && cached.regionX == entry.regionX
          && cached.regionZ == entry.regionZ
          && cached.dimension.equals(dimension)) {
        return cached;
      }
    }
    byte[] bytes = readBlock(entry);
    RecordCodec.Block block =
        RecordCodec.decodeBlock(bytes, dimension, entry.regionX, entry.regionZ);
    if (block.count != entry.count) {
      throw SpikeCodes.corrupt(
          "block for r."
              + entry.regionX
              + "."
              + entry.regionZ
              + " has "
              + block.count
              + " records; catalog says "
              + entry.count);
    }
    synchronized (blockCache) {
      RecordCodec.Block previous = blockCache.put(cacheKey, block);
      cachedBytes += block.retainedBytes() - (previous == null ? 0 : previous.retainedBytes());
      Iterator<RecordCodec.Block> it = blockCache.values().iterator();
      while (cachedBytes > CACHE_BYTES && it.hasNext()) {
        cachedBytes -= it.next().retainedBytes();
        it.remove();
      }
    }
    return block;
  }

  private byte[] readBlock(Catalog.Entry entry) throws ChunkIndexException {
    try {
      FileChannel channel = channel(entry.fileId);
      ByteBuffer buffer = ByteBuffer.allocate(entry.length);
      long position = entry.offset;
      while (buffer.hasRemaining()) {
        int n = channel.read(buffer, position);
        if (n < 0) {
          throw SpikeCodes.corrupt("file " + fileName(entry.fileId) + " is truncated");
        }
        position += n;
      }
      return buffer.array();
    } catch (NoSuchFileException e) {
      throw SpikeCodes.corrupt("missing data file " + fileName(entry.fileId), e);
    } catch (IOException e) {
      throw SpikeCodes.io("cannot read " + fileName(entry.fileId), e);
    }
  }

  private FileChannel channel(long fileId) throws IOException {
    synchronized (channels) {
      FileChannel channel = channels.get(fileId);
      if (channel == null) {
        channel = FileChannel.open(data.resolve(fileName(fileId)), StandardOpenOption.READ);
        channels.put(fileId, channel);
        if (channels.size() > OPEN_CHANNELS) {
          Iterator<FileChannel> eldest = channels.values().iterator();
          eldest.next().close();
          eldest.remove();
        }
      }
      return channel;
    }
  }

  private String fileName(long fileId) {
    return (layout == Layout.SEGMENTS ? "seg-" : "blk-") + fileId + ".dat";
  }

  class Reader extends ReaderBase {
    final Catalog snapshot;

    Reader(Catalog snapshot) {
      this.snapshot = snapshot;
    }

    @Override
    public SortedSet<DimensionKey> dimensions() {
      requireUsable();
      SortedSet<DimensionKey> out = new TreeSet<>();
      snapshot.regions.forEach(
          (d, map) -> {
            if (!map.isEmpty()) {
              out.add(d);
            }
          });
      return out;
    }

    @Override
    public Optional<ChunkProvenance> find(ChunkPosition chunk) throws ChunkIndexException {
      requireUsable();
      return Optional.ofNullable(RegionStoreIndex.this.find(snapshot, chunk));
    }

    @Override
    public ChunkCursor scan(ChunkQuery query) {
      requireUsable();
      return new RegionCursor(this, snapshot, query);
    }

    @Override
    public long count(ChunkQuery query) throws ChunkIndexException {
      requireUsable();
      long[] fast = fastCountAndBox(snapshot, query);
      return fast != null ? fast[0] : countByScan(query);
    }

    @Override
    public ChunkEnvelope envelope(ChunkQuery query) throws ChunkIndexException {
      requireUsable();
      long[] fast = fastCountAndBox(snapshot, query);
      if (fast == null || fast[1] == Long.MIN_VALUE) {
        return envelopeByScan(query);
      }
      if (fast[0] == 0) {
        return ChunkEnvelope.empty(query.dimension());
      }
      return new ChunkEnvelope.Bounded(
          new ChunkRectangle(
              query.dimension(), (int) fast[1], (int) fast[2], (int) fast[3], (int) fast[4]));
    }

    @Override
    void release() {
      readerClosed(snapshot.generation);
    }
  }

  /**
   * Streams one region at a time, skipping regions whose summary rules them out. Holds at most one
   * decoded block.
   */
  final class RegionCursor extends CursorBase {
    private final ChunkQuery query;
    private final Iterator<Catalog.Entry> regions;
    // Long.MIN_VALUE is a real key (chunk MIN_VALUE, MIN_VALUE), so "no start" needs its own flag.
    private final boolean resumes;
    private final long startAfterKey;
    private RecordCodec.@Nullable Block block;
    private int nextIndex = 1024;

    RegionCursor(ReaderBase reader, Catalog snapshot, ChunkQuery query) {
      super(reader);
      this.query = query;
      NavigableMap<Long, Catalog.Entry> map =
          snapshot.regions.getOrDefault(query.dimension(), new TreeMap<>());
      long from = Long.MIN_VALUE;
      long to = Long.MAX_VALUE;
      ChunkRectangle area = query.area();
      if (area != null) {
        from = ChunkOrder.regionStart(area.minX() >> 5, area.minZ() >> 5);
        to = ChunkOrder.regionEnd(area.maxX() >> 5, area.maxZ() >> 5);
      }
      ChunkPosition after = query.startAfter();
      resumes = after != null;
      startAfterKey = after == null ? 0 : ChunkOrder.key(after);
      if (after != null) {
        from = Math.max(from, ChunkOrder.regionStart(after.x() >> 5, after.z() >> 5));
      }
      this.regions =
          from > to
              ? java.util.Collections.emptyIterator()
              : map.subMap(from, true, to, true).values().iterator();
    }

    @Override
    @Nullable ChunkProvenance advance() throws ChunkIndexException {
      while (true) {
        RecordCodec.Block current = block;
        while (current != null && nextIndex < 1024) {
          ChunkProvenance record = current.at(nextIndex++);
          if (record != null
              && (!resumes || ChunkOrder.key(record.chunk()) > startAfterKey)
              && query.matches(record)) {
            return record;
          }
        }
        block = null;
        if (!regions.hasNext()) {
          return null;
        }
        Catalog.Entry entry = regions.next();
        ChunkRectangle area = query.area();
        if (area != null
            && (entry.regionZ < area.minZ() >> 5 || entry.regionZ > area.maxZ() >> 5)) {
          continue;
        }
        if (!summaryMayMatch(entry, query)) {
          continue;
        }
        block = block(query.dimension(), entry);
        nextIndex = 0;
      }
    }
  }

  static boolean summaryMayMatch(Catalog.Entry entry, ChunkQuery query) {
    Catalog.Summary s = entry.summary;
    boolean protection =
        switch (query.protection()) {
          case ANY -> true;
          case PROTECTED -> s.protectedCount > 0;
          case UNPROTECTED -> entry.count - s.protectedCount > 0;
        };
    return protection
        && switch (query.seasons()) {
          case SeasonFilter.Any any -> true;
          case SeasonFilter.Legacy legacy -> s.legacyCount > 0;
          case SeasonFilter.FirstObservedIn f -> seasonCount(s, f.season(), 0) > 0;
          case SeasonFilter.ActiveIn a -> seasonCount(s, a.season(), 2) > 0;
          case SeasonFilter.TouchedBy t -> seasonCount(s, t.season(), 4) > 0;
        };
  }

  private static int seasonCount(Catalog.Summary s, SeasonId season, int slot) {
    int[] c = s.seasons.get(season);
    return c == null ? 0 : c[slot];
  }

  /**
   * Count and absolute bounds {count, minX, minZ, maxX, maxZ} answered from summaries alone, or
   * {@code null} when the query needs a scan. {@code minX == Long.MIN_VALUE} means the count is
   * known but the bounds are not.
   */
  static long @Nullable [] fastCountAndBox(Catalog snapshot, ChunkQuery query) {
    if (query.area() != null || query.startAfter() != null) {
      return null;
    }
    boolean anySeason = query.seasons() instanceof SeasonFilter.Any;
    if (query.protection() != ProtectionFilter.ANY && !anySeason) {
      return null;
    }
    long count = 0;
    long minX = Long.MAX_VALUE;
    long minZ = Long.MAX_VALUE;
    long maxX = Long.MIN_VALUE;
    long maxZ = Long.MIN_VALUE;
    boolean boxKnown = query.protection() != ProtectionFilter.UNPROTECTED;
    for (Catalog.Entry e :
        snapshot.regions.getOrDefault(query.dimension(), new TreeMap<>()).values()) {
      Catalog.Summary s = e.summary;
      int n;
      int box;
      int box2 = -1;
      if (query.protection() == ProtectionFilter.PROTECTED) {
        n = s.protectedCount;
        box = s.protectedBox;
      } else if (query.protection() == ProtectionFilter.UNPROTECTED) {
        n = e.count - s.protectedCount;
        box = -1;
      } else {
        switch (query.seasons()) {
          case SeasonFilter.Any any -> {
            n = e.count;
            box = s.allBox;
          }
          case SeasonFilter.Legacy legacy -> {
            n = s.legacyCount;
            box = s.legacyBox;
          }
          case SeasonFilter.FirstObservedIn f -> {
            int[] c = s.seasons.get(f.season());
            n = c == null ? 0 : c[0];
            box = c == null ? -1 : c[1];
          }
          case SeasonFilter.ActiveIn a -> {
            int[] c = s.seasons.get(a.season());
            n = c == null ? 0 : c[2];
            box = c == null ? -1 : c[3];
          }
          case SeasonFilter.TouchedBy t -> {
            int[] c = s.seasons.get(t.season());
            n = c == null ? 0 : c[4];
            box = c == null ? -1 : c[1];
            box2 = c == null ? -1 : c[3];
          }
        }
      }
      count += n;
      for (int b : new int[] {box, box2}) {
        if (b != -1) {
          long baseX = (long) e.regionX << 5;
          long baseZ = (long) e.regionZ << 5;
          minX = Math.min(minX, baseX + (b & 0xFF));
          minZ = Math.min(minZ, baseZ + ((b >>> 8) & 0xFF));
          maxX = Math.max(maxX, baseX + ((b >>> 16) & 0xFF));
          maxZ = Math.max(maxZ, baseZ + (b >>> 24));
        }
      }
    }
    if (!boxKnown) {
      return new long[] {count, Long.MIN_VALUE, 0, 0, 0};
    }
    return new long[] {count, minX, minZ, maxX, maxZ};
  }

  // ---- writing -------------------------------------------------------------------------------

  @Override
  @Nullable ChunkProvenance committedRecord(ChunkPosition chunk) throws ChunkIndexException {
    return find(catalog, chunk);
  }

  @Override
  synchronized void commitChanges(Map<ChunkPosition, @Nullable ChunkProvenance> changes)
      throws ChunkIndexException {
    Catalog base = catalog;
    TreeMap<DimensionKey, TreeMap<Long, List<ChunkPosition>>> touched = new TreeMap<>();
    for (ChunkPosition chunk : changes.keySet()) {
      long key = ChunkOrder.key(chunk);
      touched
          .computeIfAbsent(chunk.dimension(), d -> new TreeMap<>())
          .computeIfAbsent(
              ChunkOrder.regionStart(ChunkOrder.regionX(key), ChunkOrder.regionZ(key)),
              k -> new ArrayList<>())
          .add(chunk);
    }
    TreeMap<DimensionKey, NavigableMap<Long, Catalog.Entry>> regions = new TreeMap<>(base.regions);
    List<Long> superseded = new ArrayList<>();
    List<Path> createdFiles = new ArrayList<>();
    long nextFileId = base.nextFileId;
    try {
      BlockWriter writer = new BlockWriter(nextFileId, createdFiles);
      for (var dimension : touched.entrySet()) {
        TreeMap<Long, Catalog.Entry> map =
            new TreeMap<>(base.regions.getOrDefault(dimension.getKey(), new TreeMap<>()));
        for (var region : dimension.getValue().entrySet()) {
          Catalog.Entry old = map.get(region.getKey());
          @Nullable ChunkProvenance[] records =
              old == null
                  ? new ChunkProvenance[1024]
                  : block(dimension.getKey(), old).copyRecords();
          for (ChunkPosition chunk : region.getValue()) {
            records[ChunkOrder.localIndex(ChunkOrder.key(chunk))] = changes.get(chunk);
          }
          int rx = ChunkOrder.regionX(region.getKey());
          int rz = ChunkOrder.regionZ(region.getKey());
          long[] presence = new long[16];
          int count = 0;
          for (int i = 0; i < 1024; i++) {
            if (records[i] != null) {
              presence[i >>> 6] |= 1L << (i & 63);
              count++;
            }
          }
          if (old != null) {
            superseded.add(old.fileId);
          }
          if (count == 0) {
            map.remove(region.getKey());
            continue;
          }
          byte[] bytes = RecordCodec.encodeBlock(dimension.getKey(), rx, rz, records);
          long[] location = writer.write(bytes);
          map.put(
              region.getKey(),
              new Catalog.Entry(
                  rx,
                  rz,
                  location[0],
                  location[1],
                  bytes.length,
                  presence,
                  count,
                  Catalog.Summary.of(records)));
        }
        regions.put(dimension.getKey(), map);
      }
      nextFileId = compactIfWorthwhile(regions, writer);
      writer.sync();
      Catalog provisional = new Catalog(base.generation + 1, nextFileId, null, regions);
      Catalog.Attachment attachment = writeAttachment(provisional, changes);
      Catalog next = new Catalog(base.generation + 1, nextFileId, attachment, regions);
      writeCatalog(next);
      catalog = next;
      afterCommit(next);
    } catch (ChunkIndexException | RuntimeException e) {
      abandon(createdFiles);
      throw e;
    } catch (IOException e) {
      abandon(createdFiles);
      throw SpikeCodes.io("commit failed", e);
    }
    scheduleGarbage(base, superseded);
  }

  /** C3 hook: writes an extra file committed with {@code next}. */
  Catalog.@Nullable Attachment writeAttachment(
      Catalog next, Map<ChunkPosition, @Nullable ChunkProvenance> changes)
      throws ChunkIndexException, IOException {
    return null;
  }

  /** C3 hook: called after {@code next} became the committed catalog. */
  void afterCommit(Catalog next) {}

  /** Allocates files for new blocks: a new file per block, or appends to the current segment. */
  private final class BlockWriter {
    private long nextFileId;
    private final List<Path> created;
    private final List<FileChannel> toSync = new ArrayList<>();

    BlockWriter(long nextFileId, List<Path> created) {
      this.nextFileId = nextFileId;
      this.created = created;
    }

    long[] write(byte[] bytes) throws IOException {
      if (layout == Layout.FILE_PER_REGION) {
        long id = nextFileId++;
        Path file = data.resolve(fileName(id));
        created.add(file);
        try (FileChannel channel =
            FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
          writeFully(channel, bytes, 0);
          channel.force(true);
        }
        return new long[] {id, 0};
      }
      FileChannel segment = segment();
      long offset = currentSegmentSize;
      writeFully(segment, bytes, offset);
      currentSegmentSize += bytes.length;
      return new long[] {currentSegment, offset};
    }

    private FileChannel segment() throws IOException {
      FileChannel writer = segmentWriter;
      if (writer == null || currentSegmentSize >= SEGMENT_ROLL_BYTES) {
        if (writer != null) {
          writer.force(true);
          writer.close();
        }
        currentSegment = nextFileId++;
        currentSegmentSize = 0;
        Path file = data.resolve(fileName(currentSegment));
        created.add(file);
        writer = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        segmentWriter = writer;
      }
      if (!toSync.contains(writer)) {
        toSync.add(writer);
      }
      return writer;
    }

    long nextFileId() {
      return nextFileId;
    }

    void sync() throws IOException {
      for (FileChannel channel : toSync) {
        if (channel.isOpen()) {
          channel.force(true);
        }
      }
      if (!created.isEmpty()) {
        syncDirectory(data);
      }
    }
  }

  /**
   * In segment mode, when dead bytes exceed both 64 MiB and the live bytes, copies the live blocks
   * of every mostly-dead segment forward. Blocks are position-independent, so this is a byte copy.
   */
  private long compactIfWorthwhile(
      Map<DimensionKey, NavigableMap<Long, Catalog.Entry>> regions, BlockWriter writer)
      throws IOException, ChunkIndexException {
    if (layout != Layout.SEGMENTS) {
      return writer.nextFileId();
    }
    Map<Long, Long> live = new TreeMap<>();
    for (NavigableMap<Long, Catalog.Entry> map : regions.values()) {
      for (Catalog.Entry e : map.values()) {
        live.merge(e.fileId, (long) e.length, Long::sum);
      }
    }
    long totalLive = 0;
    long totalDead = 0;
    Map<Long, Long> sizes = new TreeMap<>();
    for (Map.Entry<Long, Long> segment : live.entrySet()) {
      long id = segment.getKey();
      long size = id == currentSegment ? currentSegmentSize : sizeOf(id);
      sizes.put(id, size);
      totalLive += segment.getValue();
      totalDead += size - segment.getValue();
    }
    if (totalDead < Math.max(SEGMENT_ROLL_BYTES, totalLive)) {
      return writer.nextFileId();
    }
    for (var dimension : regions.entrySet()) {
      TreeMap<Long, Catalog.Entry> map = new TreeMap<>(dimension.getValue());
      for (var region : map.entrySet()) {
        Catalog.Entry e = region.getValue();
        long size = sizes.getOrDefault(e.fileId, 0L);
        if (e.fileId != currentSegment && live.getOrDefault(e.fileId, 0L) * 2 < size) {
          long[] location = writer.write(readBlock(e));
          region.setValue(
              new Catalog.Entry(
                  e.regionX,
                  e.regionZ,
                  location[0],
                  location[1],
                  e.length,
                  e.presence,
                  e.count,
                  e.summary));
        }
      }
      dimension.setValue(map);
    }
    return writer.nextFileId();
  }

  private long sizeOf(long fileId) throws IOException {
    try {
      return Files.size(data.resolve(fileName(fileId)));
    } catch (NoSuchFileException e) {
      return 0;
    }
  }

  private void writeCatalog(Catalog next) throws IOException {
    Path tmp = directory.resolve("catalog.tmp");
    try (FileChannel channel =
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      writeFully(channel, next.encode(), 0);
      channel.force(true);
    }
    Files.move(tmp, directory.resolve("catalog"), StandardCopyOption.ATOMIC_MOVE);
    syncDirectory(directory);
  }

  /** Writes {@code bytes} as {@code name} durably, for attachments. Returns its CRC-32C. */
  final int writeDurably(String name, byte[] bytes) throws IOException {
    Path file = directory.resolve(name);
    try (FileChannel channel =
        FileChannel.open(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      writeFully(channel, bytes, 0);
      channel.force(true);
    }
    CRC32C crc = new CRC32C();
    crc.update(bytes);
    return (int) crc.getValue();
  }

  private synchronized void scheduleGarbage(Catalog superseded, List<Long> fileIds) {
    long generation = superseded.generation + 1;
    if (layout == Layout.FILE_PER_REGION) {
      for (long id : fileIds) {
        pendingDeletes.add(new long[] {generation, id});
      }
    } else {
      Set<Long> referenced = new HashSet<>();
      for (NavigableMap<Long, Catalog.Entry> map : catalog.regions.values()) {
        for (Catalog.Entry e : map.values()) {
          referenced.add(e.fileId);
        }
      }
      Set<Long> before = new HashSet<>();
      for (NavigableMap<Long, Catalog.Entry> map : superseded.regions.values()) {
        for (Catalog.Entry e : map.values()) {
          before.add(e.fileId);
        }
      }
      for (long id : before) {
        if (!referenced.contains(id) && id != currentSegment) {
          pendingDeletes.add(new long[] {generation, id});
        }
      }
    }
    collectGarbage();
  }

  /** Deletes superseded files that no open reader's snapshot can reach. */
  private synchronized void collectGarbage() {
    long oldestReader =
        readersByGeneration.isEmpty() ? Long.MAX_VALUE : readersByGeneration.firstKey();
    Iterator<long[]> it = pendingDeletes.iterator();
    while (it.hasNext()) {
      long[] pending = it.next();
      if (pending[0] <= oldestReader) {
        synchronized (channels) {
          FileChannel open = channels.remove(pending[1]);
          if (open != null) {
            try {
              open.close();
            } catch (IOException ignored) {
              // Closing a read-only channel cannot lose data.
            }
          }
        }
        try {
          Files.deleteIfExists(data.resolve(fileName(pending[1])));
        } catch (IOException ignored) {
          // Left for a later collection; an unreferenced file is harmless.
          continue;
        }
        it.remove();
      }
    }
  }

  @Override
  public void verify() throws ChunkIndexException {
    requireOpen();
    Catalog onDisk;
    try {
      onDisk = Catalog.decode(Files.readAllBytes(directory.resolve("catalog")));
    } catch (NoSuchFileException e) {
      return;
    } catch (IOException e) {
      throw SpikeCodes.io("cannot read catalog", e);
    }
    for (var dimension : onDisk.regions.entrySet()) {
      for (Catalog.Entry e : dimension.getValue().values()) {
        RecordCodec.Block block =
            RecordCodec.decodeBlock(readBlock(e), dimension.getKey(), e.regionX, e.regionZ);
        @Nullable ChunkProvenance[] records = block.copyRecords();
        for (int i = 0; i < 1024; i++) {
          if ((records[i] != null) != e.present(i)) {
            throw SpikeCodes.corrupt("presence mismatch in r." + e.regionX + "." + e.regionZ);
          }
        }
        ByteWriter expected = new ByteWriter(256);
        Catalog.Summary.of(records).write(expected);
        ByteWriter actual = new ByteWriter(256);
        e.summary.write(actual);
        if (!java.util.Arrays.equals(expected.toByteArray(), actual.toByteArray())) {
          throw SpikeCodes.corrupt("summary mismatch in r." + e.regionX + "." + e.regionZ);
        }
      }
    }
  }

  @Override
  void closeStorage() {
    synchronized (channels) {
      for (FileChannel channel : channels.values()) {
        try {
          channel.close();
        } catch (IOException ignored) {
          // Read-only.
        }
      }
      channels.clear();
    }
    FileChannel writer = segmentWriter;
    if (writer != null) {
      try {
        writer.close();
      } catch (IOException ignored) {
        // Everything committed was already synced.
      }
    }
  }

  static void writeFully(FileChannel channel, byte[] bytes, long position) throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    long at = position;
    while (buffer.hasRemaining()) {
      at += channel.write(buffer, at);
    }
  }

  static void syncDirectory(Path directory) throws IOException {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  /**
   * Cleans up after a failed commit: files created by it are unreferenced, so they are deleted, and
   * a segment it opened is abandoned so the next commit starts a fresh one.
   */
  private void abandon(List<Path> createdFiles) {
    FileChannel writer = segmentWriter;
    if (writer != null && createdFiles.contains(data.resolve(fileName(currentSegment)))) {
      try {
        writer.close();
      } catch (IOException ignored) {
        // The segment is being discarded.
      }
      segmentWriter = null;
      currentSegment = -1;
    }
    deleteQuietly(createdFiles);
  }

  private static void deleteQuietly(List<Path> files) {
    for (Path file : files) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException ignored) {
        // An orphan block is unreferenced and therefore harmless.
      }
    }
  }
}
