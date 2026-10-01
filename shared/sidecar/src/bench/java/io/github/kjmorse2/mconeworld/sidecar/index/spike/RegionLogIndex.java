// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkEnvelope;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkCursor;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexBatch;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.CRC32C;
import org.jspecify.annotations.Nullable;

/**
 * C1-log: C1-segment region blocks as a <b>checkpoint</b>, plus an append-only <b>delta log</b>.
 * This is the log-structured refinement measured after C1-segments showed copy-on-write
 * amplification on scattered updates.
 *
 * <ul>
 *   <li><b>Commit</b> appends one framed delta (the changed records) to {@code deltas-<g>.log} and
 *       syncs it: one {@code fsync}, with cost proportional to the batch rather than to the regions
 *       it touches. {@code g} is the generation of the checkpoint catalog the log applies to.
 *   <li><b>Reads</b> consult an in-memory overlay (the replayed deltas, newest wins) before the
 *       checkpoint blocks. Scans merge the two in key order.
 *   <li><b>{@link #checkpoint()}</b> folds the overlay into the region blocks through C1's commit
 *       (catalog swap), then starts a new, empty log for the new generation. It runs when called,
 *       or inside a commit once the overlay exceeds {@value #OVERLAY_LIMIT} records, which bounds
 *       memory.
 *   <li><b>Open</b> replays the log matching the catalog generation up to its first invalid frame
 *       (the torn tail of an unacknowledged commit). A log for an older generation is stale (a
 *       checkpoint finished after it) and is ignored. A newer one is corruption.
 * </ul>
 *
 * <p>Delta frame: {@code u32 length | payload | u32 CRC-32C(payload)}; payload: varint season
 * count, 16-byte seasons, varint change count, then per change: utf dimension, i64 key, u8 present,
 * and if present a {@link RecordCodec} record (base second 0).
 */
public final class RegionLogIndex extends SpikeIndexBase {
  static final int OVERLAY_LIMIT = 100_000;
  private static final byte[] LOG_MAGIC = {'M', 'C', 'O', 'L'};

  private final Path directory;
  private final RegionStoreIndex base;
  private volatile Overlay overlay = Overlay.EMPTY;
  private @Nullable FileChannel log;
  private long logGeneration;

  /** Immutable overlay: per dimension, key → record, or {@code null} for "removed". */
  static final class Overlay {
    static final Overlay EMPTY = new Overlay(Map.of(), 0);

    final Map<DimensionKey, NavigableMap<Long, @Nullable ChunkProvenance>> dimensions;
    final int size;

    Overlay(Map<DimensionKey, NavigableMap<Long, @Nullable ChunkProvenance>> dimensions, int size) {
      this.dimensions = dimensions;
      this.size = size;
    }

    Overlay plus(Map<ChunkPosition, @Nullable ChunkProvenance> changes) {
      Map<DimensionKey, NavigableMap<Long, @Nullable ChunkProvenance>> next =
          new HashMap<>(dimensions);
      Map<DimensionKey, NavigableMap<Long, @Nullable ChunkProvenance>> copied = new HashMap<>();
      for (Map.Entry<ChunkPosition, @Nullable ChunkProvenance> change : changes.entrySet()) {
        DimensionKey d = change.getKey().dimension();
        NavigableMap<Long, @Nullable ChunkProvenance> map = copied.get(d);
        if (map == null) {
          map = new TreeMap<>(dimensions.getOrDefault(d, new TreeMap<>()));
          copied.put(d, map);
          next.put(d, map);
        }
        map.put(ChunkOrder.key(change.getKey()), change.getValue());
      }
      int total = 0;
      for (NavigableMap<Long, @Nullable ChunkProvenance> map : next.values()) {
        total += map.size();
      }
      return new Overlay(next, total);
    }
  }

  private RegionLogIndex(Path directory) throws ChunkIndexException {
    this.directory = directory;
    this.base = RegionStoreIndex.open(directory, RegionStoreIndex.Layout.SEGMENTS);
    long generation = base.committedCatalog().generation;
    this.logGeneration = generation;
    Path file = logFile(generation);
    try {
      if (Files.exists(file)) {
        overlay = replay(Files.readAllBytes(file), generation, false);
      }
      try (var others = Files.list(directory)) {
        for (Path p : (Iterable<Path>) others::iterator) {
          String name = p.getFileName().toString();
          if (name.startsWith("deltas-") && name.endsWith(".log")) {
            long g = Long.parseLong(name.substring(7, name.length() - 4));
            if (g > generation) {
              throw SpikeCodes.corrupt(
                  name + " is newer than the catalog (generation " + generation + ")");
            }
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      base.close();
      throw e instanceof ChunkIndexException c ? c : SpikeCodes.corrupt("cannot open delta log", e);
    }
  }

  /** Opens (or creates) a C1-log index in {@code directory}. */
  public static RegionLogIndex open(Path directory) throws ChunkIndexException {
    return new RegionLogIndex(directory);
  }

  private Path logFile(long generation) {
    return directory.resolve("deltas-" + generation + ".log");
  }

  // ---- delta log -----------------------------------------------------------------------------

  /**
   * Replays frames. With {@code strict}, any invalid frame is corruption; otherwise the first
   * invalid frame ends the log (a torn tail).
   */
  private static Overlay replay(byte[] bytes, long generation, boolean strict)
      throws ChunkIndexException {
    ByteReader header = new ByteReader(bytes, 0, bytes.length, "delta log");
    for (byte b : LOG_MAGIC) {
      if (header.u8() != (b & 0xFF)) {
        throw SpikeCodes.corrupt("delta log: bad magic");
      }
    }
    if (header.i64() != generation) {
      throw SpikeCodes.corrupt("delta log: generation mismatch");
    }
    Overlay result = Overlay.EMPTY;
    int position = header.position();
    while (position < bytes.length) {
      if (bytes.length - position < 8) {
        if (strict) {
          throw SpikeCodes.corrupt("delta log: torn frame at " + position);
        }
        break;
      }
      ByteReader frame = new ByteReader(bytes, position, bytes.length - position, "delta frame");
      int length = frame.i32();
      if (length < 0 || length > bytes.length - position - 8) {
        if (strict) {
          throw SpikeCodes.corrupt("delta log: bad frame length at " + position);
        }
        break;
      }
      CRC32C crc = new CRC32C();
      crc.update(bytes, position + 4, length);
      int stored = new ByteReader(bytes, position + 4 + length, 4, "delta crc").i32();
      if (stored != (int) crc.getValue()) {
        if (strict) {
          throw SpikeCodes.corrupt("delta log: CRC mismatch at " + position);
        }
        break;
      }
      result = result.plus(decodeDelta(new ByteReader(bytes, position + 4, length, "delta")));
      position += 8 + length;
    }
    return result;
  }

  private static Map<ChunkPosition, @Nullable ChunkProvenance> decodeDelta(ByteReader in)
      throws ChunkIndexException {
    int seasonCount = in.varInt(65_535);
    SeasonId[] seasons = new SeasonId[seasonCount];
    for (int i = 0; i < seasonCount; i++) {
      try {
        seasons[i] = SeasonId.of(new UUID(in.i64(), in.i64()));
      } catch (RuntimeException e) {
        throw SpikeCodes.corrupt("delta: invalid season", e);
      }
    }
    int count = in.varInt(OVERLAY_LIMIT * 4);
    Map<ChunkPosition, @Nullable ChunkProvenance> changes = new HashMap<>();
    for (int i = 0; i < count; i++) {
      DimensionKey dimension;
      try {
        dimension = DimensionKey.parse(in.utf(DimensionKey.MAX_LENGTH * 4));
      } catch (RuntimeException e) {
        throw SpikeCodes.corrupt("delta: invalid dimension", e);
      }
      ChunkPosition chunk = ChunkOrder.position(dimension, in.i64());
      int present = in.u8();
      if (present > 1) {
        throw SpikeCodes.corrupt("delta: bad presence flag");
      }
      changes.put(
          chunk,
          present == 0
              ? null
              : RecordCodec.readRecord(in, chunk, k -> k < seasons.length ? seasons[k] : null, 0));
    }
    if (in.remaining() != 0) {
      throw SpikeCodes.corrupt("delta: trailing bytes");
    }
    return changes;
  }

  private static byte[] encodeDelta(Map<ChunkPosition, @Nullable ChunkProvenance> changes) {
    TreeMap<SeasonId, Integer> dictionary = new TreeMap<>();
    for (ChunkProvenance r : changes.values()) {
      if (r == null) {
        continue;
      }
      SeasonId first = r.firstObservation().knownSeason();
      if (first != null) {
        dictionary.put(first, 0);
      }
      for (SeasonActivity a : r.activity()) {
        dictionary.put(a.season(), 0);
      }
    }
    int next = 0;
    for (Map.Entry<SeasonId, Integer> e : dictionary.entrySet()) {
      e.setValue(next++);
    }
    ByteWriter out = new ByteWriter(64 + changes.size() * 48);
    out.varInt(dictionary.size());
    for (SeasonId s : dictionary.keySet()) {
      out.i64(s.value().getMostSignificantBits()).i64(s.value().getLeastSignificantBits());
    }
    out.varInt(changes.size());
    for (Map.Entry<ChunkPosition, @Nullable ChunkProvenance> e : changes.entrySet()) {
      out.utf(e.getKey().dimension().namespacedId()).i64(ChunkOrder.key(e.getKey()));
      ChunkProvenance r = e.getValue();
      if (r == null) {
        out.u8(0);
      } else {
        out.u8(1);
        RecordCodec.writeRecord(out, r, dictionary::get, 0);
      }
    }
    return out.toByteArray();
  }

  private FileChannel openLog() throws IOException {
    FileChannel channel = log;
    if (channel != null) {
      return channel;
    }
    Path file = logFile(logGeneration);
    boolean created = !Files.exists(file);
    channel =
        FileChannel.open(
            file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
    if (created) {
      ByteWriter header = new ByteWriter(16);
      header.bytes(LOG_MAGIC).i64(logGeneration);
      RegionStoreIndex.writeFully(channel, header.toByteArray(), 0);
      channel.force(true);
      RegionStoreIndex.syncDirectory(directory);
    } else if (validLength(Files.readAllBytes(file)) != channel.size()) {
      // Frames appended after a torn tail would be unreachable by replay, so never append here.
      channel.close();
      throw new TornLogException();
    }
    log = channel;
    return channel;
  }

  /** The log ends in an unacknowledged, partial frame: checkpoint before appending. */
  private static final class TornLogException extends IOException {
    private static final long serialVersionUID = 1L;
  }

  private static int validLength(byte[] bytes) {
    int position = LOG_MAGIC.length + 8;
    while (bytes.length - position >= 8) {
      int length =
          ((bytes[position] & 0xFF) << 24)
              | ((bytes[position + 1] & 0xFF) << 16)
              | ((bytes[position + 2] & 0xFF) << 8)
              | (bytes[position + 3] & 0xFF);
      if (length < 0 || length > bytes.length - position - 8) {
        return position;
      }
      CRC32C crc = new CRC32C();
      crc.update(bytes, position + 4, length);
      int stored =
          ((bytes[position + 4 + length] & 0xFF) << 24)
              | ((bytes[position + 5 + length] & 0xFF) << 16)
              | ((bytes[position + 6 + length] & 0xFF) << 8)
              | (bytes[position + 7 + length] & 0xFF);
      if (stored != (int) crc.getValue()) {
        return position;
      }
      position += 8 + length;
    }
    return position;
  }

  // ---- writing -------------------------------------------------------------------------------

  @Override
  @Nullable ChunkProvenance committedRecord(ChunkPosition chunk) throws ChunkIndexException {
    Overlay current = overlay;
    NavigableMap<Long, @Nullable ChunkProvenance> map = current.dimensions.get(chunk.dimension());
    long key = ChunkOrder.key(chunk);
    if (map != null && map.containsKey(key)) {
      return map.get(key);
    }
    return base.find(base.committedCatalog(), chunk);
  }

  @Override
  synchronized void commitChanges(Map<ChunkPosition, @Nullable ChunkProvenance> changes)
      throws ChunkIndexException {
    if (changes.isEmpty()) {
      return;
    }
    byte[] payload = encodeDelta(changes);
    CRC32C crc = new CRC32C();
    crc.update(payload);
    ByteWriter frame = new ByteWriter(payload.length + 8);
    frame.i32(payload.length).bytes(payload).i32((int) crc.getValue());
    try {
      FileChannel channel;
      try {
        channel = openLog();
      } catch (TornLogException e) {
        checkpoint(true); // folds the replayed overlay and starts a fresh log
        channel = openLog();
      }
      RegionStoreIndex.writeFully(channel, frame.toByteArray(), channel.size());
      channel.force(false); // fdatasync: also persists the new file length
    } catch (IOException e) {
      throw SpikeCodes.io("delta append failed", e);
    }
    overlay = overlay.plus(changes);
    if (overlay.size > OVERLAY_LIMIT) {
      checkpoint(true);
    }
  }

  /**
   * Folds the overlay into the region blocks (C1's commit) and starts an empty log for the new
   * generation. A crash at any point leaves either the old catalog and its log, or the new catalog
   * (whose old log is then stale and ignored).
   */
  public synchronized void checkpoint() throws ChunkIndexException {
    checkpoint(false);
  }

  private void checkpoint(boolean evenIfEmpty) throws ChunkIndexException {
    requireOpen();
    Overlay current = overlay;
    if (current.size == 0 && !evenIfEmpty) {
      return;
    }
    try (ChunkIndexBatch batch = base.begin()) {
      for (var dimension : current.dimensions.entrySet()) {
        for (var entry : dimension.getValue().entrySet()) {
          ChunkPosition chunk = ChunkOrder.position(dimension.getKey(), entry.getKey());
          ChunkProvenance record = entry.getValue();
          batch.apply(
              record == null ? new ChunkMutation.Remove(chunk) : new ChunkMutation.Replace(record));
        }
      }
      batch.commit();
    }
    long oldGeneration = logGeneration;
    FileChannel old = log;
    log = null;
    logGeneration = base.committedCatalog().generation;
    overlay = Overlay.EMPTY;
    try {
      if (old != null) {
        old.close();
      }
      Files.deleteIfExists(logFile(oldGeneration));
    } catch (IOException ignored) {
      // A stale log for an older generation is ignored on open.
    }
  }

  @Override
  public void verify() throws ChunkIndexException {
    requireOpen();
    base.verify();
    try {
      byte[] bytes = Files.readAllBytes(logFile(logGeneration));
      replay(bytes, logGeneration, true);
    } catch (NoSuchFileException e) {
      // No commits since the last checkpoint.
    } catch (IOException e) {
      throw SpikeCodes.io("cannot read delta log", e);
    }
  }

  @Override
  void closeStorage() {
    FileChannel channel = log;
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException ignored) {
        // Every acknowledged frame was synced.
      }
    }
    base.close();
  }

  // ---- reading -------------------------------------------------------------------------------

  @Override
  public synchronized ChunkIndexReader reader() throws ChunkIndexException {
    requireOpen();
    return new Reader(base.reader(), overlay);
  }

  private final class Reader extends ReaderBase {
    private final ChunkIndexReader baseReader;
    private final Overlay snapshot;

    Reader(ChunkIndexReader baseReader, Overlay snapshot) {
      this.baseReader = baseReader;
      this.snapshot = snapshot;
    }

    @Override
    public SortedSet<DimensionKey> dimensions() throws ChunkIndexException {
      requireUsable();
      SortedSet<DimensionKey> out = new TreeSet<>();
      TreeSet<DimensionKey> candidates = new TreeSet<>(baseReader.dimensions());
      candidates.addAll(snapshot.dimensions.keySet());
      for (DimensionKey d : candidates) {
        try (ChunkCursor cursor = scan(ChunkQuery.all(d))) {
          if (cursor.next() != null) {
            out.add(d);
          }
        }
      }
      return out;
    }

    @Override
    public Optional<ChunkProvenance> find(ChunkPosition chunk) throws ChunkIndexException {
      requireUsable();
      NavigableMap<Long, @Nullable ChunkProvenance> map =
          snapshot.dimensions.get(chunk.dimension());
      long key = ChunkOrder.key(chunk);
      if (map != null && map.containsKey(key)) {
        return Optional.ofNullable(map.get(key));
      }
      return baseReader.find(chunk);
    }

    @Override
    public ChunkCursor scan(ChunkQuery query) throws ChunkIndexException {
      requireUsable();
      NavigableMap<Long, @Nullable ChunkProvenance> map =
          snapshot.dimensions.getOrDefault(query.dimension(), Collections.emptyNavigableMap());
      return new MergeCursor(this, baseReader.scan(query), map, query);
    }

    @Override
    public long count(ChunkQuery query) throws ChunkIndexException {
      requireUsable();
      return overlayTouches(query) ? countByScan(query) : baseReader.count(query);
    }

    @Override
    public ChunkEnvelope envelope(ChunkQuery query) throws ChunkIndexException {
      requireUsable();
      return overlayTouches(query) ? envelopeByScan(query) : baseReader.envelope(query);
    }

    private boolean overlayTouches(ChunkQuery query) {
      NavigableMap<Long, @Nullable ChunkProvenance> map =
          snapshot.dimensions.get(query.dimension());
      return map != null && !map.isEmpty();
    }

    @Override
    void release() {
      baseReader.close();
    }
  }

  /**
   * Merges the checkpoint cursor with overlay entries in key order. A base record is suppressed
   * when the overlay holds its key; overlay records are filtered through the query.
   */
  private static final class MergeCursor extends CursorBase {
    private final ChunkCursor base;
    private final NavigableMap<Long, @Nullable ChunkProvenance> overlay;
    private final Iterator<Map.Entry<Long, @Nullable ChunkProvenance>> pending;
    private final ChunkQuery query;
    private @Nullable ChunkProvenance nextBase;
    private boolean baseLoaded;
    private Map.@Nullable Entry<Long, @Nullable ChunkProvenance> nextOverlay;

    MergeCursor(
        ReaderBase reader,
        ChunkCursor base,
        NavigableMap<Long, @Nullable ChunkProvenance> overlay,
        ChunkQuery query) {
      super(reader);
      this.base = base;
      this.overlay = overlay;
      this.query = query;
      long from = Long.MIN_VALUE;
      long to = Long.MAX_VALUE;
      ChunkRectangle area = query.area();
      if (area != null) {
        from = ChunkOrder.regionStart(area.minX() >> 5, area.minZ() >> 5);
        to = ChunkOrder.regionEnd(area.maxX() >> 5, area.maxZ() >> 5);
      }
      this.pending = overlay.subMap(from, true, to, true).entrySet().iterator();
      this.nextOverlay = matchingOverlay();
    }

    private Map.@Nullable Entry<Long, @Nullable ChunkProvenance> matchingOverlay() {
      while (pending.hasNext()) {
        Map.Entry<Long, @Nullable ChunkProvenance> e = pending.next();
        ChunkProvenance r = e.getValue();
        if (r != null && query.matches(r)) {
          return e;
        }
      }
      return null;
    }

    @Override
    @Nullable ChunkProvenance advance() throws ChunkIndexException {
      if (!baseLoaded) {
        nextBase = nextUnshadowedBase();
        baseLoaded = true;
      }
      ChunkProvenance b = nextBase;
      Map.Entry<Long, @Nullable ChunkProvenance> o = nextOverlay;
      if (b == null && o == null) {
        return null;
      }
      if (o == null || (b != null && ChunkOrder.key(b.chunk()) < o.getKey())) {
        baseLoaded = false;
        return b;
      }
      nextOverlay = matchingOverlay();
      return o.getValue();
    }

    private @Nullable ChunkProvenance nextUnshadowedBase() throws ChunkIndexException {
      for (ChunkProvenance r = base.next(); r != null; r = base.next()) {
        if (!overlay.containsKey(ChunkOrder.key(r.chunk()))) {
          return r;
        }
      }
      return null;
    }

    @Override
    void release() {
      base.close();
    }
  }
}
