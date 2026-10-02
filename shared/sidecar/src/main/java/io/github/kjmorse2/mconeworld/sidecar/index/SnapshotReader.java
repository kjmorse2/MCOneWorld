// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkEnvelope;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * A reader over one {@link IndexSnapshot}. Checkpoint records come from region blocks; logged
 * mutations are folded over them. Scans read one block at a time and skip regions whose catalog
 * summary rules them out; counts and envelopes of whole-dimension queries come from summaries alone
 * when the log does not touch the dimension.
 *
 * <p>Use from one thread at a time. Closing it invalidates its cursors; the owning handle's closure
 * invalidates everything.
 */
final class SnapshotReader implements ChunkIndexSnapshot {
  /** The handle that opened this reader. */
  interface Owner {
    /** Throws {@link IllegalStateException} if the handle is closed. */
    void requireOpen();

    void released(IndexSnapshot snapshot);
  }

  private final Owner owner;
  private final IndexData data;
  private final IndexSnapshot snapshot;
  private boolean closed;

  SnapshotReader(Owner owner, IndexData data, IndexSnapshot snapshot) {
    this.owner = owner;
    this.data = data;
    this.snapshot = snapshot;
  }

  private void requireUsable() {
    owner.requireOpen();
    if (closed) {
      throw new IllegalStateException("The reader is closed");
    }
  }

  @Override
  public ChunkIndexState state() {
    requireUsable();
    return snapshot.state();
  }

  @Override
  public SortedSet<DimensionKey> dimensions() throws ChunkIndexException {
    requireUsable();
    TreeSet<DimensionKey> candidates = new TreeSet<>(snapshot.catalog().regions.keySet());
    candidates.addAll(snapshot.overlay().dimensions().keySet());
    SortedSet<DimensionKey> out = new TreeSet<>();
    for (DimensionKey d : candidates) {
      if (!snapshot.overlay().touches(d)) {
        if (!snapshot.catalog().regions.getOrDefault(d, new TreeMap<>()).isEmpty()) {
          out.add(d);
        }
        continue;
      }
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
    return Optional.ofNullable(resolved(chunk));
  }

  private @Nullable ChunkProvenance resolved(ChunkPosition chunk) throws ChunkIndexException {
    List<ChunkMutation> logged = snapshot.overlay().get(chunk);
    ChunkProvenance stored = stored(chunk);
    return logged == null ? stored : Overlay.resolve(stored, logged);
  }

  /** The checkpoint record of {@code chunk}, ignoring the log. */
  private @Nullable ChunkProvenance stored(ChunkPosition chunk) throws ChunkIndexException {
    long key = ChunkOrder.key(chunk);
    Catalog.Entry entry = snapshot.catalog().entry(chunk.dimension(), key);
    return entry == null ? null : data.record(chunk.dimension(), entry, ChunkOrder.localIndex(key));
  }

  @Override
  public boolean contains(ChunkPosition chunk) throws ChunkIndexException {
    requireUsable();
    List<ChunkMutation> logged = snapshot.overlay().get(chunk);
    boolean exists = snapshot.catalog().present(chunk.dimension(), ChunkOrder.key(chunk));
    if (logged != null) {
      for (ChunkMutation m : logged) {
        exists = Overlay.existsAfter(m, exists);
      }
    }
    return exists;
  }

  @Override
  public ChunkCursor scan(ChunkQuery query) throws ChunkIndexException {
    requireUsable();
    RegionCursor checkpoint = new RegionCursor(query);
    if (!snapshot.overlay().touches(query.dimension())) {
      return checkpoint;
    }
    return new MergeCursor(checkpoint, snapshot.overlay().of(query.dimension()), query);
  }

  @Override
  public long count(ChunkQuery query) throws ChunkIndexException {
    requireUsable();
    long[] fast = summaryCountAndBox(query);
    return fast != null ? fast[0] : ChunkIndexSnapshot.super.count(query);
  }

  @Override
  public ChunkEnvelope envelope(ChunkQuery query) throws ChunkIndexException {
    requireUsable();
    long[] fast = summaryCountAndBox(query);
    if (fast == null || fast[1] == Long.MIN_VALUE) {
      return ChunkIndexSnapshot.super.envelope(query);
    }
    if (fast[0] == 0) {
      return ChunkEnvelope.empty(query.dimension());
    }
    return new ChunkEnvelope.Bounded(
        new ChunkRectangle(
            query.dimension(), (int) fast[1], (int) fast[2], (int) fast[3], (int) fast[4]));
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      owner.released(snapshot);
    }
  }

  // ---- summaries -----------------------------------------------------------------------------

  private static boolean summaryMayMatch(Catalog.Entry entry, ChunkQuery query) {
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
   * {count, minX, minZ, maxX, maxZ} from summaries alone, or null when the query needs a scan.
   * {@code minX == Long.MIN_VALUE} means the count is known but the bounds are not.
   */
  private long @Nullable [] summaryCountAndBox(ChunkQuery query) {
    if (query.area() != null
        || query.startAfter() != null
        || snapshot.overlay().touches(query.dimension())) {
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
    for (Catalog.Entry e :
        snapshot.catalog().regions.getOrDefault(query.dimension(), new TreeMap<>()).values()) {
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
    if (query.protection() == ProtectionFilter.UNPROTECTED) {
      return new long[] {count, Long.MIN_VALUE, 0, 0, 0};
    }
    return new long[] {count, minX, minZ, maxX, maxZ};
  }

  // ---- cursors -------------------------------------------------------------------------------

  /** Shared lifecycle checks. */
  private abstract class CursorBase implements ChunkCursor {
    private boolean cursorClosed;

    @Override
    public final @Nullable ChunkProvenance next() throws ChunkIndexException {
      requireUsable();
      if (cursorClosed) {
        throw new IllegalStateException("The cursor is closed");
      }
      return advance();
    }

    abstract @Nullable ChunkProvenance advance() throws ChunkIndexException;

    @Override
    public final void close() {
      cursorClosed = true;
    }
  }

  /**
   * Checkpoint records only, one region block at a time, skipping regions whose summary rules them
   * out. Holds at most one block and one decoded unit.
   */
  private final class RegionCursor extends CursorBase {
    private final ChunkQuery query;
    private final Iterator<Catalog.Entry> regions;
    // Long.MIN_VALUE is a real key (chunk MIN_VALUE, MIN_VALUE), so "no start" has its own flag.
    private final boolean resumes;
    private final long startAfterKey;
    private IndexData.@Nullable WholeBlock block;
    private BlockCodec.@Nullable Unit unit;
    private int unitIndex = -1;
    private int nextLocal = 1024;

    RegionCursor(ChunkQuery query) {
      this.query = query;
      NavigableMap<Long, Catalog.Entry> map =
          snapshot.catalog().regions.getOrDefault(query.dimension(), new TreeMap<>());
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
      regions =
          from > to
              ? Collections.emptyIterator()
              : map.subMap(from, true, to, true).values().iterator();
    }

    @Override
    @Nullable ChunkProvenance advance() throws ChunkIndexException {
      while (true) {
        IndexData.WholeBlock current = block;
        while (current != null && nextLocal < 1024) {
          int local = nextLocal++;
          if (!current.header.present(local)) {
            continue;
          }
          int u = current.header.unitOf(local);
          BlockCodec.Unit loaded = unit;
          if (loaded == null || unitIndex != u) {
            loaded = current.unit(u);
            unit = loaded;
            unitIndex = u;
          }
          ChunkProvenance record = loaded.record(local);
          if (record != null
              && (!resumes || ChunkOrder.key(record.chunk()) > startAfterKey)
              && query.matches(record)) {
            return record;
          }
        }
        block = null;
        unit = null;
        unitIndex = -1;
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
        block = data.block(query.dimension(), entry);
        nextLocal = 0;
      }
    }
  }

  /**
   * Checkpoint scan merged with logged chunks in key order. A checkpoint record whose chunk has
   * logged mutations is suppressed; the logged chunk is resolved (checkpoint record plus mutations)
   * and emitted if it matches. Resolving uses point lookups, served in key order by the cache.
   */
  private final class MergeCursor extends CursorBase {
    private final RegionCursor stored;
    private final NavigableMap<Long, List<ChunkMutation>> logged;
    private final Iterator<Map.Entry<Long, List<ChunkMutation>>> pending;
    private final ChunkQuery query;
    private @Nullable ChunkProvenance nextStored;
    private boolean storedLoaded;
    private @Nullable ChunkProvenance nextLogged;
    private boolean loggedLoaded;

    MergeCursor(
        RegionCursor stored, NavigableMap<Long, List<ChunkMutation>> logged, ChunkQuery query) {
      this.stored = stored;
      this.logged = logged;
      this.query = query;
      long from = Long.MIN_VALUE;
      long to = Long.MAX_VALUE;
      ChunkRectangle area = query.area();
      if (area != null) {
        from = ChunkOrder.regionStart(area.minX() >> 5, area.minZ() >> 5);
        to = ChunkOrder.regionEnd(area.maxX() >> 5, area.maxZ() >> 5);
      }
      pending = logged.subMap(from, true, to, true).entrySet().iterator();
    }

    private @Nullable ChunkProvenance nextResolved() throws ChunkIndexException {
      while (pending.hasNext()) {
        Map.Entry<Long, List<ChunkMutation>> e = pending.next();
        ChunkPosition chunk = ChunkOrder.position(query.dimension(), e.getKey());
        ChunkProvenance r = Overlay.resolve(stored(chunk), e.getValue());
        if (r != null && query.matches(r)) {
          return r;
        }
      }
      return null;
    }

    private @Nullable ChunkProvenance nextUnshadowed() throws ChunkIndexException {
      for (ChunkProvenance r = stored.advance(); r != null; r = stored.advance()) {
        if (!logged.containsKey(ChunkOrder.key(r.chunk()))) {
          return r;
        }
      }
      return null;
    }

    @Override
    @Nullable ChunkProvenance advance() throws ChunkIndexException {
      if (!storedLoaded) {
        nextStored = nextUnshadowed();
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
  }
}
