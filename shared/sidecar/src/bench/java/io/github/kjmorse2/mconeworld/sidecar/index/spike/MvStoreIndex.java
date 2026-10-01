// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkCursor;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery;
import io.github.kjmorse2.mconeworld.sidecar.index.ProtectionFilter;
import io.github.kjmorse2.mconeworld.sidecar.index.SeasonFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.h2.mvstore.Cursor;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.RootReference;
import org.jspecify.annotations.Nullable;

/**
 * C4: H2 MVStore, a pure-Java, log-structured, copy-on-write B-tree store in one file.
 *
 * <p>Maps:
 *
 * <ul>
 *   <li>{@code dims}: id → dimension key; {@code seasons}: id → 16-byte season UUID
 *   <li>{@code c.<dim>}: {@link ChunkOrder} key → record ({@link RecordCodec}, global season ids)
 *   <li>secondary key sets: {@code f.<dim>.<season>} first observed, {@code a.<dim>.<season>}
 *       active, {@code l.<dim>} legacy, {@code p.<dim>} protected
 * </ul>
 *
 * <p>A commit is {@code commit()} then {@code sync()}. A reader registers version usage, which
 * keeps the chunks of its version from being reused, and captures every map's root, which is an
 * immutable copy-on-write tree.
 */
public final class MvStoreIndex extends SpikeIndexBase {
  private static final Boolean PRESENT = Boolean.TRUE;

  private final MVStore store;
  private final MVMap<Integer, String> dims;
  private final MVMap<Integer, byte[]> seasons;
  private final Map<DimensionKey, Integer> dimensionIds = new HashMap<>();
  private final Map<SeasonId, Integer> seasonIds = new HashMap<>();
  private final Map<Integer, SeasonId> seasonsById = new HashMap<>();

  private MvStoreIndex(Path file) throws ChunkIndexException {
    try {
      store =
          new MVStore.Builder()
              .fileName(file.toString())
              .autoCommitDisabled()
              .compress()
              .cacheSize(64)
              .open();
      store.setVersionsToKeep(0);
      dims = store.openMap("dims");
      seasons = store.openMap("seasons");
      for (Map.Entry<Integer, String> e : dims.entrySet()) {
        dimensionIds.put(DimensionKey.parse(e.getValue()), e.getKey());
      }
      for (Map.Entry<Integer, byte[]> e : seasons.entrySet()) {
        SeasonId id = SeasonId.of(uuid(e.getValue()));
        seasonIds.put(id, e.getKey());
        seasonsById.put(e.getKey(), id);
      }
    } catch (RuntimeException e) {
      throw failure("cannot open " + file, e);
    }
  }

  /** Opens (or creates) a C4 index in {@code directory}. */
  public static MvStoreIndex open(Path directory) throws ChunkIndexException {
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw SpikeCodes.io("cannot create " + directory, e);
    }
    return new MvStoreIndex(directory.resolve("index.mv"));
  }

  private MVMap<Long, byte[]> records(int dim) {
    return store.openMap("c." + dim);
  }

  private MVMap<Long, Boolean> set(String name) {
    return store.openMap(name);
  }

  // ---- reading -------------------------------------------------------------------------------

  @Override
  public synchronized ChunkIndexReader reader() throws ChunkIndexException {
    requireOpen();
    try {
      MVStore.TxCounter usage = store.registerVersionUsage();
      Map<String, Snapshot<?>> roots = new HashMap<>();
      for (String name : store.getMapNames()) {
        if (!name.equals("dims") && !name.equals("seasons")) {
          MVMap<Long, Object> map = store.openMap(name);
          roots.put(name, new Snapshot<>(map, map.getRoot()));
        }
      }
      return new Reader(usage, roots, new HashMap<>(dimensionIds), new HashMap<>(seasonIds));
    } catch (RuntimeException e) {
      throw failure("cannot open a reader", e);
    }
  }

  private final class Reader extends ReaderBase {
    private final MVStore.TxCounter usage;
    private final Map<String, Snapshot<?>> roots;
    private final Map<DimensionKey, Integer> dimensionIds;
    private final Map<SeasonId, Integer> seasonIds;

    Reader(
        MVStore.TxCounter usage,
        Map<String, Snapshot<?>> roots,
        Map<DimensionKey, Integer> dimensionIds,
        Map<SeasonId, Integer> seasonIds) {
      this.usage = usage;
      this.roots = roots;
      this.dimensionIds = dimensionIds;
      this.seasonIds = seasonIds;
    }

    @SuppressWarnings("unchecked")
    @Nullable Snapshot<byte[]> recordRoot(int dim) {
      return (Snapshot<byte[]>) roots.get("c." + dim);
    }

    @SuppressWarnings("unchecked")
    @Nullable Snapshot<Boolean> setRoot(String name) {
      return (Snapshot<Boolean>) roots.get(name);
    }

    @Override
    public SortedSet<DimensionKey> dimensions() {
      requireUsable();
      SortedSet<DimensionKey> out = new TreeSet<>();
      dimensionIds.forEach(
          (key, id) -> {
            Snapshot<byte[]> root = recordRoot(id);
            if (root != null && root.root().root.getTotalCount() > 0) {
              out.add(key);
            }
          });
      return out;
    }

    @Override
    public Optional<ChunkProvenance> find(ChunkPosition chunk) throws ChunkIndexException {
      requireUsable();
      Integer dim = dimensionIds.get(chunk.dimension());
      Snapshot<byte[]> root = dim == null ? null : recordRoot(dim);
      if (root == null) {
        return Optional.empty();
      }
      return Optional.ofNullable(get(root, chunk));
    }

    @Override
    public ChunkCursor scan(ChunkQuery query) {
      requireUsable();
      return new MvCursor(this, query);
    }

    @Override
    void release() {
      store.deregisterVersionUsage(usage);
    }
  }

  private @Nullable ChunkProvenance get(Snapshot<byte[]> snapshot, ChunkPosition chunk)
      throws ChunkIndexException {
    try {
      byte[] value = snapshot.map().get(snapshot.root().root, ChunkOrder.key(chunk));
      return value == null ? null : decode(chunk, value);
    } catch (RuntimeException e) {
      throw failure("cannot read " + chunk, e);
    }
  }

  /** A map and its root at the reader's version: an immutable copy-on-write tree. */
  private record Snapshot<V>(MVMap<Long, V> map, RootReference<Long, V> root) {}

  private ChunkProvenance decode(ChunkPosition chunk, byte[] value) throws ChunkIndexException {
    return RecordCodec.readRecord(
        new ByteReader(value, 0, value.length, "record " + chunk), chunk, seasonsById::get, 0);
  }

  /**
   * Iterates the narrowest key source for the query (a secondary set when the season or protection
   * filter allows it, otherwise the record map) within the key range, and filters through {@link
   * ChunkQuery#matches}.
   */
  private final class MvCursor extends CursorBase {
    private final ChunkQuery query;
    private final @Nullable Snapshot<byte[]> records;
    private final Iterator<Long> keys;
    private final long last;

    MvCursor(Reader reader, ChunkQuery query) {
      super(reader);
      this.query = query;
      Integer dim = reader.dimensionIds.get(query.dimension());
      this.records = dim == null ? null : reader.recordRoot(dim);
      long first = Long.MIN_VALUE;
      long end = Long.MAX_VALUE;
      ChunkRectangle area = query.area();
      if (area != null) {
        first = ChunkOrder.regionStart(area.minX() >> 5, area.minZ() >> 5);
        end = ChunkOrder.regionEnd(area.maxX() >> 5, area.maxZ() >> 5);
      }
      ChunkPosition after = query.startAfter();
      if (after != null) {
        long key = ChunkOrder.key(after);
        first = key == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(first, key + 1);
      }
      this.last = end;
      if (dim == null || records == null || first > end) {
        keys = java.util.Collections.emptyIterator();
        return;
      }
      List<String> sources = keySources(query, dim, reader.seasonIds);
      if (sources == null) {
        keys = new Cursor<>(records.root(), first, end);
      } else if (sources.isEmpty()) {
        keys = java.util.Collections.emptyIterator();
      } else if (sources.size() == 2) {
        keys =
            merge(
                cursor(reader, sources.get(0), first, end),
                cursor(reader, sources.get(1), first, end));
      } else {
        keys = cursor(reader, sources.get(0), first, end);
      }
    }

    private Iterator<Long> cursor(Reader reader, String name, long from, long to) {
      Snapshot<Boolean> root = reader.setRoot(name);
      return root == null
          ? java.util.Collections.emptyIterator()
          : new Cursor<>(root.root(), from, to);
    }

    @Override
    @Nullable ChunkProvenance advance() throws ChunkIndexException {
      Snapshot<byte[]> root = records;
      try {
        while (root != null && keys.hasNext()) {
          long key = keys.next();
          if (key > last) {
            return null;
          }
          ChunkPosition chunk = ChunkOrder.position(query.dimension(), key);
          ChunkRectangle area = query.area();
          if (area != null && !area.contains(chunk)) {
            continue;
          }
          ChunkProvenance record = get(root, chunk);
          if (record == null) {
            throw SpikeCodes.corrupt("secondary set names " + chunk + ", which has no record");
          }
          if (query.matches(record)) {
            return record;
          }
        }
        return null;
      } catch (RuntimeException e) {
        throw failure("scan failed", e);
      }
    }
  }

  /**
   * The secondary sets to iterate (two are merged), an empty list for "no results", or {@code null}
   * to iterate the record map itself.
   */
  private static @Nullable List<String> keySources(
      ChunkQuery query, int dim, Map<SeasonId, Integer> seasonIds) {
    return switch (query.seasons()) {
      case SeasonFilter.Legacy legacy -> List.of("l." + dim);
      case SeasonFilter.FirstObservedIn f ->
          seasonIds.containsKey(f.season())
              ? List.of("f." + dim + "." + seasonIds.get(f.season()))
              : List.of();
      case SeasonFilter.ActiveIn a ->
          seasonIds.containsKey(a.season())
              ? List.of("a." + dim + "." + seasonIds.get(a.season()))
              : List.of();
      case SeasonFilter.TouchedBy t ->
          seasonIds.containsKey(t.season())
              ? List.of(
                  "f." + dim + "." + seasonIds.get(t.season()),
                  "a." + dim + "." + seasonIds.get(t.season()))
              : List.of();
      case SeasonFilter.Any any ->
          query.protection() == ProtectionFilter.PROTECTED ? List.of("p." + dim) : null;
    };
  }

  private static Iterator<Long> merge(Iterator<Long> a, Iterator<Long> b) {
    return new Iterator<>() {
      private @Nullable Long nextA = a.hasNext() ? a.next() : null;
      private @Nullable Long nextB = b.hasNext() ? b.next() : null;

      @Override
      public boolean hasNext() {
        return nextA != null || nextB != null;
      }

      @Override
      public Long next() {
        Long x = nextA;
        Long y = nextB;
        long out;
        if (y == null || (x != null && x < y)) {
          out = java.util.Objects.requireNonNull(x);
          nextA = a.hasNext() ? a.next() : null;
        } else if (x == null || y < x) {
          out = y;
          nextB = b.hasNext() ? b.next() : null;
        } else {
          out = x;
          nextA = a.hasNext() ? a.next() : null;
          nextB = b.hasNext() ? b.next() : null;
        }
        return out;
      }
    };
  }

  // ---- writing -------------------------------------------------------------------------------

  @Override
  synchronized @Nullable ChunkProvenance committedRecord(ChunkPosition chunk)
      throws ChunkIndexException {
    Integer dim = dimensionIds.get(chunk.dimension());
    if (dim == null) {
      return null;
    }
    try {
      byte[] value = records(dim).get(ChunkOrder.key(chunk));
      return value == null ? null : decode(chunk, value);
    } catch (RuntimeException e) {
      throw failure("cannot read " + chunk, e);
    }
  }

  @Override
  synchronized void commitChanges(Map<ChunkPosition, @Nullable ChunkProvenance> changes)
      throws ChunkIndexException {
    Set<DimensionKey> newDimensions = new HashSet<>();
    Set<SeasonId> newSeasons = new HashSet<>();
    ByteWriter buffer = new ByteWriter(256);
    try {
      for (Map.Entry<ChunkPosition, @Nullable ChunkProvenance> change : changes.entrySet()) {
        ChunkPosition chunk = change.getKey();
        int dim = dimensionId(chunk.dimension(), newDimensions);
        long key = ChunkOrder.key(chunk);
        MVMap<Long, byte[]> map = records(dim);
        byte[] oldValue = map.get(key);
        if (oldValue != null) {
          updateSets(dim, key, decode(chunk, oldValue), false);
        }
        ChunkProvenance record = change.getValue();
        if (record == null) {
          map.remove(key);
          continue;
        }
        buffer.reset();
        for (SeasonId s : seasonsOf(record)) {
          seasonId(s, newSeasons);
        }
        RecordCodec.writeRecord(buffer, record, seasonIds::get, 0);
        map.put(key, buffer.toByteArray());
        updateSets(dim, key, record, true);
      }
      store.commit();
      store.sync();
    } catch (RuntimeException e) {
      store.rollback();
      newDimensions.forEach(dimensionIds::remove);
      newSeasons.forEach(s -> seasonsById.remove(seasonIds.remove(s)));
      throw failure("commit failed", e);
    }
  }

  private void updateSets(int dim, long key, ChunkProvenance record, boolean add) {
    SeasonId first = record.firstObservation().knownSeason();
    if (first != null) {
      toggle(set("f." + dim + "." + seasonIds.get(first)), key, add);
    } else {
      toggle(set("l." + dim), key, add);
    }
    for (SeasonActivity a : record.activity()) {
      toggle(set("a." + dim + "." + seasonIds.get(a.season())), key, add);
    }
    if (record.protection() == ChunkProtection.PROTECTED) {
      toggle(set("p." + dim), key, add);
    }
  }

  private static void toggle(MVMap<Long, Boolean> set, long key, boolean add) {
    if (add) {
      set.put(key, PRESENT);
    } else {
      set.remove(key);
    }
  }

  private static Set<SeasonId> seasonsOf(ChunkProvenance record) {
    Set<SeasonId> out = new HashSet<>();
    SeasonId first = record.firstObservation().knownSeason();
    if (first != null) {
      out.add(first);
    }
    for (SeasonActivity a : record.activity()) {
      out.add(a.season());
    }
    return out;
  }

  private int dimensionId(DimensionKey key, Set<DimensionKey> created) {
    Integer id = dimensionIds.get(key);
    if (id != null) {
      return id;
    }
    int next = dimensionIds.size() + 1;
    dims.put(next, key.namespacedId());
    dimensionIds.put(key, next);
    created.add(key);
    return next;
  }

  private int seasonId(SeasonId season, Set<SeasonId> created) {
    Integer id = seasonIds.get(season);
    if (id != null) {
      return id;
    }
    int next = seasonIds.size() + 1;
    byte[] bytes = new byte[16];
    long msb = season.value().getMostSignificantBits();
    long lsb = season.value().getLeastSignificantBits();
    for (int i = 0; i < 8; i++) {
      bytes[i] = (byte) (msb >>> (56 - 8 * i));
      bytes[8 + i] = (byte) (lsb >>> (56 - 8 * i));
    }
    seasons.put(next, bytes);
    seasonIds.put(season, next);
    seasonsById.put(next, season);
    created.add(season);
    return next;
  }

  private static UUID uuid(byte[] bytes) {
    long msb = 0;
    long lsb = 0;
    for (int i = 0; i < 8; i++) {
      msb = msb << 8 | (bytes[i] & 0xFF);
      lsb = lsb << 8 | (bytes[8 + i] & 0xFF);
    }
    return new UUID(msb, lsb);
  }

  /**
   * MVStore has no public integrity check, so this reads every record and checks the secondary sets
   * against them in both directions. Chunk and page checksums are verified by MVStore as pages
   * load.
   */
  @Override
  public synchronized void verify() throws ChunkIndexException {
    requireOpen();
    try {
      Map<String, Long> expectedSizes = new HashMap<>();
      for (Map.Entry<DimensionKey, Integer> dimension : dimensionIds.entrySet()) {
        int dim = dimension.getValue();
        for (Map.Entry<Long, byte[]> e : records(dim).entrySet()) {
          ChunkPosition chunk = ChunkOrder.position(dimension.getKey(), e.getKey());
          ChunkProvenance record = decode(chunk, e.getValue());
          SeasonId first = record.firstObservation().knownSeason();
          String firstSet = first == null ? "l." + dim : "f." + dim + "." + seasonIds.get(first);
          require(set(firstSet).containsKey(e.getKey()), firstSet, chunk);
          expectedSizes.merge(firstSet, 1L, Long::sum);
          for (SeasonActivity a : record.activity()) {
            String name = "a." + dim + "." + seasonIds.get(a.season());
            require(set(name).containsKey(e.getKey()), name, chunk);
            expectedSizes.merge(name, 1L, Long::sum);
          }
          if (record.protection() == ChunkProtection.PROTECTED) {
            require(set("p." + dim).containsKey(e.getKey()), "p." + dim, chunk);
            expectedSizes.merge("p." + dim, 1L, Long::sum);
          }
        }
      }
      for (String name : store.getMapNames()) {
        if (name.startsWith("f.")
            || name.startsWith("a.")
            || name.startsWith("l.")
            || name.startsWith("p.")) {
          if (set(name).sizeAsLong() != expectedSizes.getOrDefault(name, 0L)) {
            throw SpikeCodes.corrupt("secondary set " + name + " has extra entries");
          }
        }
      }
    } catch (RuntimeException e) {
      throw failure("verification failed", e);
    }
  }

  private static void require(boolean condition, String set, ChunkPosition chunk)
      throws ChunkIndexException {
    if (!condition) {
      throw SpikeCodes.corrupt("secondary set " + set + " is missing " + chunk);
    }
  }

  private static ChunkIndexException failure(String message, RuntimeException e) {
    String text = e.getClass().getName() + ": " + e.getMessage();
    boolean corrupt =
        text.contains("corrupt")
            || text.contains("Corrupt")
            || text.contains("checksum")
            || text.contains("FILE_CORRUPT")
            || e instanceof IllegalStateException
            || e instanceof ArrayIndexOutOfBoundsException
            || e instanceof ClassCastException;
    return corrupt
        ? SpikeCodes.corrupt(message + ": " + text, e)
        : SpikeCodes.io(message + ": " + text, e);
  }

  @Override
  synchronized void closeStorage() {
    try {
      store.close();
    } catch (RuntimeException ignored) {
      // Everything committed was synced.
    }
  }
}
