// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkEnvelope;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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
 * C1-log: C1-segment region blocks as a <b>checkpoint</b>, plus an append-only <b>log of
 * mutations</b>. This is the log-structured refinement measured after C1-segments showed
 * copy-on-write amplification on scattered updates.
 *
 * <ul>
 *   <li><b>Commit</b> validates every mutation by <em>existence</em> alone (the catalog's presence
 *       bitmaps plus earlier logged mutations; see {@link SpikeIndexBase#deferredMutations()}). It
 *       appends the mutations as one CRC-framed delta to {@code deltas-<g>.log} and runs one {@code
 *       fdatasync}. It never reads a record, so its cost is proportional to the batch.
 *   <li><b>Reads</b> fold a chunk's logged mutations over its checkpoint record through {@link
 *       ChunkMutation#applyTo}, the contract's single definition of semantics. Scans merge the
 *       logged chunks into the checkpoint stream in key order.
 *   <li><b>{@link #checkpoint()}</b> resolves every logged chunk, writes the results through C1's
 *       commit (new blocks, then a catalog swap), and starts an empty log for the new generation.
 *       It runs when called, or inside a commit once {@value #OVERLAY_LIMIT} mutations are logged,
 *       which bounds memory.
 *   <li><b>Open</b> replays the log matching the catalog generation, up to its first invalid frame
 *       (the torn tail of an unacknowledged commit). A log for an older generation is stale (a
 *       checkpoint finished after it) and is ignored. A newer one is corruption. A log with a torn
 *       tail is never appended to: the next commit checkpoints first.
 * </ul>
 *
 * <p>Delta frame: {@code u32 length | payload | u32 CRC-32C(payload)}. Payload: varint season count
 * and 16-byte seasons; varint chunk count; per chunk: utf dimension, i64 key, varint mutation
 * count, then per mutation a type byte (1 ObserveFirst, 2 RecordActivity, 3 SetProtection, 4
 * Replace, 5 Remove) and its fields.
 */
public final class RegionLogIndex extends SpikeIndexBase {
  static final int OVERLAY_LIMIT = 100_000;
  private static final byte[] LOG_MAGIC = {'M', 'C', 'O', 'L'};

  private final Path directory;
  private final RegionStoreIndex base;
  private volatile Overlay overlay = Overlay.EMPTY;
  private @Nullable FileChannel log;
  private long logGeneration;

  /** Immutable: per dimension, chunk key → the mutations logged since the checkpoint, in order. */
  static final class Overlay {
    static final Overlay EMPTY = new Overlay(Map.of(), 0);

    final Map<DimensionKey, NavigableMap<Long, List<ChunkMutation>>> dimensions;
    final int mutations;

    Overlay(Map<DimensionKey, NavigableMap<Long, List<ChunkMutation>>> dimensions, int mutations) {
      this.dimensions = dimensions;
      this.mutations = mutations;
    }

    @Nullable List<ChunkMutation> get(ChunkPosition chunk) {
      NavigableMap<Long, List<ChunkMutation>> map = dimensions.get(chunk.dimension());
      return map == null ? null : map.get(ChunkOrder.key(chunk));
    }

    Overlay plus(Map<ChunkPosition, List<ChunkMutation>> added) {
      Map<DimensionKey, NavigableMap<Long, List<ChunkMutation>>> next = new HashMap<>(dimensions);
      Map<DimensionKey, NavigableMap<Long, List<ChunkMutation>>> copied = new HashMap<>();
      int count = mutations;
      for (Map.Entry<ChunkPosition, List<ChunkMutation>> e : added.entrySet()) {
        DimensionKey d = e.getKey().dimension();
        NavigableMap<Long, List<ChunkMutation>> map = copied.get(d);
        if (map == null) {
          map = new TreeMap<>(dimensions.getOrDefault(d, new TreeMap<>()));
          copied.put(d, map);
          next.put(d, map);
        }
        long key = ChunkOrder.key(e.getKey());
        List<ChunkMutation> merged = new ArrayList<>(map.getOrDefault(key, List.of()));
        merged.addAll(e.getValue());
        map.put(key, List.copyOf(merged));
        count += e.getValue().size();
      }
      return new Overlay(next, count);
    }
  }

  private RegionLogIndex(Path directory) throws ChunkIndexException {
    this.directory = directory;
    this.base = RegionStoreIndex.open(directory, RegionStoreIndex.Layout.SEGMENTS);
    long generation = base.committedCatalog().generation;
    this.logGeneration = generation;
    try {
      Path file = logFile(generation);
      if (Files.exists(file)) {
        overlay = replay(Files.readAllBytes(file), generation, false);
      }
      try (var others = Files.list(directory)) {
        for (Path p : (Iterable<Path>) others::iterator) {
          String name = p.getFileName().toString();
          if (name.startsWith("deltas-")
              && name.endsWith(".log")
              && Long.parseLong(name.substring(7, name.length() - 4)) > generation) {
            throw SpikeCodes.corrupt(name + " is newer than the catalog (" + generation + ")");
          }
        }
      }
    } catch (ChunkIndexException e) {
      base.close();
      throw e;
    } catch (IOException | RuntimeException e) {
      base.close();
      throw SpikeCodes.corrupt("cannot open delta log", e);
    }
  }

  /** Opens (or creates) a C1-log index in {@code directory}. */
  public static RegionLogIndex open(Path directory) throws ChunkIndexException {
    return new RegionLogIndex(directory);
  }

  private Path logFile(long generation) {
    return directory.resolve("deltas-" + generation + ".log");
  }

  @Override
  boolean deferredMutations() {
    return true;
  }

  /** Folds logged mutations over the checkpoint record; they were validated when committed. */
  static @Nullable ChunkProvenance resolve(
      @Nullable ChunkProvenance checkpoint, List<ChunkMutation> mutations)
      throws ChunkIndexException {
    ChunkProvenance record = checkpoint;
    for (ChunkMutation mutation : mutations) {
      try {
        record = mutation.applyTo(record);
      } catch (ChunkIndexException e) {
        throw SpikeCodes.corrupt("logged mutation no longer applies to " + mutation.chunk(), e);
      }
    }
    return record;
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
    int end = validLength(bytes);
    if (strict && end != bytes.length) {
      throw SpikeCodes.corrupt("delta log: invalid frame at " + end);
    }
    Overlay result = Overlay.EMPTY;
    int position = LOG_MAGIC.length + 8;
    while (position < end) {
      int length = new ByteReader(bytes, position, 4, "delta length").i32();
      result = result.plus(decodeDelta(new ByteReader(bytes, position + 4, length, "delta")));
      position += 8 + length;
    }
    return result;
  }

  /** The length of the prefix made of complete, CRC-valid frames. */
  private static int validLength(byte[] bytes) throws ChunkIndexException {
    int position = LOG_MAGIC.length + 8;
    while (bytes.length - position >= 8) {
      int length = new ByteReader(bytes, position, 4, "delta length").i32();
      if (length < 0 || length > bytes.length - position - 8) {
        return position;
      }
      CRC32C crc = new CRC32C();
      crc.update(bytes, position + 4, length);
      int stored = new ByteReader(bytes, position + 4 + length, 4, "delta crc").i32();
      if (stored != (int) crc.getValue()) {
        return position;
      }
      position += 8 + length;
    }
    return position;
  }

  private static Map<ChunkPosition, List<ChunkMutation>> decodeDelta(ByteReader in)
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
    int chunks = in.varInt(OVERLAY_LIMIT * 4);
    Map<ChunkPosition, List<ChunkMutation>> out = new LinkedHashMap<>();
    for (int i = 0; i < chunks; i++) {
      DimensionKey dimension;
      try {
        dimension = DimensionKey.parse(in.utf(DimensionKey.MAX_LENGTH * 4));
      } catch (RuntimeException e) {
        throw SpikeCodes.corrupt("delta: invalid dimension", e);
      }
      ChunkPosition chunk = ChunkOrder.position(dimension, in.i64());
      int count = in.varInt(OVERLAY_LIMIT * 4);
      List<ChunkMutation> mutations = new ArrayList<>(count);
      for (int m = 0; m < count; m++) {
        mutations.add(readMutation(in, chunk, seasons));
      }
      out.put(chunk, mutations);
    }
    if (in.remaining() != 0) {
      throw SpikeCodes.corrupt("delta: trailing bytes");
    }
    return out;
  }

  private static ChunkMutation readMutation(ByteReader in, ChunkPosition chunk, SeasonId[] seasons)
      throws ChunkIndexException {
    int type = in.u8();
    try {
      return switch (type) {
        case 1 -> {
          boolean legacy = in.u8() == 1;
          SeasonId season = legacy ? null : season(seasons, in.varInt(Integer.MAX_VALUE));
          Instant at = Instant.ofEpochSecond(in.zigZag(), in.varInt(999_999_999));
          var evidence = EvidenceCodes.decode(in.u8());
          yield new ChunkMutation.ObserveFirst(
              chunk,
              season == null
                  ? new FirstObservation.Legacy(at, evidence)
                  : new FirstObservation.InSeason(season, at, evidence));
        }
        case 2 -> {
          SeasonId season = season(seasons, in.varInt(Integer.MAX_VALUE));
          Instant at = Instant.ofEpochSecond(in.zigZag(), in.varInt(999_999_999));
          yield new ChunkMutation.RecordActivity(
              chunk, new SeasonActivity(season, at, EvidenceCodes.decode(in.u8())));
        }
        case 3 -> {
          int value = in.u8();
          if (value > 1) {
            throw SpikeCodes.corrupt("delta: protection code " + value);
          }
          yield new ChunkMutation.SetProtection(
              chunk, value == 1 ? ChunkProtection.PROTECTED : ChunkProtection.NONE);
        }
        case 4 ->
            new ChunkMutation.Replace(
                RecordCodec.readRecord(in, chunk, k -> k < seasons.length ? seasons[k] : null, 0));
        case 5 -> new ChunkMutation.Remove(chunk);
        default -> throw SpikeCodes.corrupt("delta: mutation type " + type);
      };
    } catch (RuntimeException e) {
      throw SpikeCodes.corrupt("delta: invalid mutation for " + chunk, e);
    }
  }

  private static SeasonId season(SeasonId[] seasons, int index) throws ChunkIndexException {
    if (index >= seasons.length) {
      throw SpikeCodes.corrupt("delta: season index " + index);
    }
    return seasons[index];
  }

  private static byte[] encodeDelta(Map<ChunkPosition, List<ChunkMutation>> mutations) {
    TreeMap<SeasonId, Integer> dictionary = new TreeMap<>();
    for (List<ChunkMutation> list : mutations.values()) {
      for (ChunkMutation m : list) {
        switch (m) {
          case ChunkMutation.ObserveFirst o -> {
            SeasonId s = o.first().knownSeason();
            if (s != null) {
              dictionary.put(s, 0);
            }
          }
          case ChunkMutation.RecordActivity a -> dictionary.put(a.activity().season(), 0);
          case ChunkMutation.Replace r -> {
            SeasonId s = r.record().firstObservation().knownSeason();
            if (s != null) {
              dictionary.put(s, 0);
            }
            r.record().activity().forEach(a -> dictionary.put(a.season(), 0));
          }
          case ChunkMutation.SetProtection p -> {}
          case ChunkMutation.Remove r -> {}
        }
      }
    }
    int next = 0;
    for (Map.Entry<SeasonId, Integer> e : dictionary.entrySet()) {
      e.setValue(next++);
    }
    ByteWriter out = new ByteWriter(64 + mutations.size() * 48);
    out.varInt(dictionary.size());
    for (SeasonId s : dictionary.keySet()) {
      out.i64(s.value().getMostSignificantBits()).i64(s.value().getLeastSignificantBits());
    }
    out.varInt(mutations.size());
    for (Map.Entry<ChunkPosition, List<ChunkMutation>> e : mutations.entrySet()) {
      out.utf(e.getKey().dimension().namespacedId()).i64(ChunkOrder.key(e.getKey()));
      out.varInt(e.getValue().size());
      for (ChunkMutation m : e.getValue()) {
        switch (m) {
          case ChunkMutation.ObserveFirst o -> {
            SeasonId s = o.first().knownSeason();
            out.u8(1).u8(s == null ? 1 : 0);
            if (s != null) {
              out.varInt(index(dictionary, s));
            }
            Instant at = o.first().observedAt();
            out.zigZag(at.getEpochSecond()).varInt(at.getNano());
            out.u8(EvidenceCodes.encode(o.first().evidence()));
          }
          case ChunkMutation.RecordActivity a -> {
            Instant at = a.activity().lastObservedAt();
            out.u8(2).varInt(index(dictionary, a.activity().season()));
            out.zigZag(at.getEpochSecond()).varInt(at.getNano());
            out.u8(EvidenceCodes.encode(a.activity().evidence()));
          }
          case ChunkMutation.SetProtection p ->
              out.u8(3).u8(p.protection() == ChunkProtection.PROTECTED ? 1 : 0);
          case ChunkMutation.Replace r -> {
            out.u8(4);
            RecordCodec.writeRecord(out, r.record(), s -> index(dictionary, s), 0);
          }
          case ChunkMutation.Remove r -> out.u8(5);
        }
      }
    }
    return out.toByteArray();
  }

  private static int index(Map<SeasonId, Integer> dictionary, SeasonId season) {
    Integer index = dictionary.get(season);
    if (index == null) {
      throw new IllegalStateException("season missing from the delta dictionary: " + season);
    }
    return index;
  }

  /** Signals that the log ends in a partial frame and must not be appended to. */
  private static final class TornLogException extends IOException {
    private static final long serialVersionUID = 1L;
  }

  private FileChannel openLog() throws IOException, ChunkIndexException {
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
      channel.close();
      throw new TornLogException();
    }
    log = channel;
    return channel;
  }

  // ---- writing -------------------------------------------------------------------------------

  @Override
  boolean committedExists(ChunkPosition chunk) {
    boolean exists = RegionStoreIndex.present(base.committedCatalog(), chunk);
    List<ChunkMutation> logged = overlay.get(chunk);
    if (logged != null) {
      for (ChunkMutation m : logged) {
        exists = existsAfter(m, exists);
      }
    }
    return exists;
  }

  @Override
  @Nullable ChunkProvenance committedRecord(ChunkPosition chunk) throws ChunkIndexException {
    ChunkProvenance checkpoint = base.find(base.committedCatalog(), chunk);
    List<ChunkMutation> logged = overlay.get(chunk);
    return logged == null ? checkpoint : resolve(checkpoint, logged);
  }

  @Override
  void commitChanges(Map<ChunkPosition, @Nullable ChunkProvenance> changes)
      throws ChunkIndexException {
    Map<ChunkPosition, List<ChunkMutation>> mutations = new LinkedHashMap<>();
    for (Map.Entry<ChunkPosition, @Nullable ChunkProvenance> e : changes.entrySet()) {
      ChunkProvenance r = e.getValue();
      mutations.put(
          e.getKey(),
          List.of(r == null ? new ChunkMutation.Remove(e.getKey()) : new ChunkMutation.Replace(r)));
    }
    commitMutations(mutations);
  }

  @Override
  synchronized void commitMutations(Map<ChunkPosition, List<ChunkMutation>> mutations)
      throws ChunkIndexException {
    if (mutations.isEmpty()) {
      return;
    }
    byte[] payload = encodeDelta(mutations);
    CRC32C crc = new CRC32C();
    crc.update(payload);
    ByteWriter frame = new ByteWriter(payload.length + 8);
    frame.i32(payload.length).bytes(payload).i32((int) crc.getValue());
    try {
      FileChannel channel;
      try {
        channel = openLog();
      } catch (TornLogException e) {
        checkpoint(true); // folds the replayed log into blocks and starts a fresh log
        channel = openLog();
      }
      RegionStoreIndex.writeFully(channel, frame.toByteArray(), channel.size());
      channel.force(false); // fdatasync: also persists the new file length
    } catch (IOException e) {
      throw SpikeCodes.io("delta append failed", e);
    }
    overlay = overlay.plus(mutations);
    if (overlay.mutations > OVERLAY_LIMIT) {
      checkpoint(true);
    }
  }

  /**
   * Folds the log into the region blocks (C1's commit) and starts an empty log for the new
   * generation. A crash at any point leaves either the old catalog and its log, or the new catalog
   * (whose old log is then stale and ignored).
   */
  public synchronized void checkpoint() throws ChunkIndexException {
    checkpoint(false);
  }

  private void checkpoint(boolean evenIfEmpty) throws ChunkIndexException {
    requireOpen();
    Overlay current = overlay;
    if (current.mutations == 0 && !evenIfEmpty) {
      return;
    }
    Catalog catalog = base.committedCatalog();
    try (ChunkIndexBatch batch = base.begin()) {
      for (var dimension : current.dimensions.entrySet()) {
        for (var entry : dimension.getValue().entrySet()) {
          ChunkPosition chunk = ChunkOrder.position(dimension.getKey(), entry.getKey());
          ChunkProvenance record = resolve(base.find(catalog, chunk), entry.getValue());
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
      replay(Files.readAllBytes(logFile(logGeneration)), logGeneration, true);
    } catch (NoSuchFileException e) {
      // No commits since the last checkpoint.
    } catch (IOException e) {
      throw SpikeCodes.io("cannot read delta log", e);
    }
    // Every logged chunk must still resolve against its checkpoint record.
    Catalog catalog = base.committedCatalog();
    for (var dimension : overlay.dimensions.entrySet()) {
      for (var entry : dimension.getValue().entrySet()) {
        ChunkPosition chunk = ChunkOrder.position(dimension.getKey(), entry.getKey());
        resolve(base.find(catalog, chunk), entry.getValue());
      }
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
    private final ChunkIndexReader checkpoint;
    private final Overlay snapshot;

    Reader(ChunkIndexReader checkpoint, Overlay snapshot) {
      this.checkpoint = checkpoint;
      this.snapshot = snapshot;
    }

    @Override
    public SortedSet<DimensionKey> dimensions() throws ChunkIndexException {
      requireUsable();
      TreeSet<DimensionKey> candidates = new TreeSet<>(checkpoint.dimensions());
      candidates.addAll(snapshot.dimensions.keySet());
      SortedSet<DimensionKey> out = new TreeSet<>();
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
      List<ChunkMutation> logged = snapshot.get(chunk);
      Optional<ChunkProvenance> stored = checkpoint.find(chunk);
      return logged == null ? stored : Optional.ofNullable(resolve(stored.orElse(null), logged));
    }

    @Override
    public ChunkCursor scan(ChunkQuery query) throws ChunkIndexException {
      requireUsable();
      NavigableMap<Long, List<ChunkMutation>> map =
          snapshot.dimensions.getOrDefault(query.dimension(), Collections.emptyNavigableMap());
      return new MergeCursor(this, checkpoint, map, query);
    }

    @Override
    public long count(ChunkQuery query) throws ChunkIndexException {
      requireUsable();
      return touches(query) ? countByScan(query) : checkpoint.count(query);
    }

    @Override
    public ChunkEnvelope envelope(ChunkQuery query) throws ChunkIndexException {
      requireUsable();
      return touches(query) ? envelopeByScan(query) : checkpoint.envelope(query);
    }

    private boolean touches(ChunkQuery query) {
      NavigableMap<Long, List<ChunkMutation>> map = snapshot.dimensions.get(query.dimension());
      return map != null && !map.isEmpty();
    }

    @Override
    void release() {
      checkpoint.close();
    }
  }

  /**
   * Merges the checkpoint's filtered scan with the logged chunks in key order. A checkpoint record
   * whose key has logged mutations is suppressed; the logged chunk is resolved (checkpoint record
   * plus mutations) and emitted if it matches. Resolving goes through point lookups, which the
   * block cache serves in key order.
   */
  private static final class MergeCursor extends CursorBase {
    private final ChunkCursor stored;
    private final ChunkIndexReader checkpoint;
    private final NavigableMap<Long, List<ChunkMutation>> logged;
    private final Iterator<Map.Entry<Long, List<ChunkMutation>>> pending;
    private final ChunkQuery query;
    private @Nullable ChunkProvenance nextStored;
    private boolean storedLoaded;
    private @Nullable ChunkProvenance nextLogged;
    private boolean loggedLoaded;

    MergeCursor(
        ReaderBase reader,
        ChunkIndexReader checkpoint,
        NavigableMap<Long, List<ChunkMutation>> logged,
        ChunkQuery query)
        throws ChunkIndexException {
      super(reader);
      this.stored = checkpoint.scan(query);
      this.checkpoint = checkpoint;
      this.logged = logged;
      this.query = query;
      long from = Long.MIN_VALUE;
      long to = Long.MAX_VALUE;
      ChunkRectangle area = query.area();
      if (area != null) {
        from = ChunkOrder.regionStart(area.minX() >> 5, area.minZ() >> 5);
        to = ChunkOrder.regionEnd(area.maxX() >> 5, area.maxZ() >> 5);
      }
      this.pending = logged.subMap(from, true, to, true).entrySet().iterator();
    }

    private @Nullable ChunkProvenance nextResolved() throws ChunkIndexException {
      while (pending.hasNext()) {
        Map.Entry<Long, List<ChunkMutation>> e = pending.next();
        ChunkPosition chunk = ChunkOrder.position(query.dimension(), e.getKey());
        ChunkProvenance r = resolve(checkpoint.find(chunk).orElse(null), e.getValue());
        if (r != null && query.matches(r)) {
          return r;
        }
      }
      return null;
    }

    private @Nullable ChunkProvenance nextUnshadowedStored() throws ChunkIndexException {
      for (ChunkProvenance r = stored.next(); r != null; r = stored.next()) {
        if (!logged.containsKey(ChunkOrder.key(r.chunk()))) {
          return r;
        }
      }
      return null;
    }

    @Override
    @Nullable ChunkProvenance advance() throws ChunkIndexException {
      if (!storedLoaded) {
        nextStored = nextUnshadowedStored();
        storedLoaded = true;
      }
      if (!loggedLoaded) {
        nextLogged = nextResolved();
        loggedLoaded = true;
      }
      ChunkProvenance s = nextStored;
      ChunkProvenance o = nextLogged;
      if (s == null && o == null) {
        return null;
      }
      if (o == null || (s != null && ChunkOrder.key(s.chunk()) < ChunkOrder.key(o.chunk()))) {
        storedLoaded = false;
        return s;
      }
      loggedLoaded = false;
      return o;
    }

    @Override
    void release() {
      stored.close();
    }
  }
}
