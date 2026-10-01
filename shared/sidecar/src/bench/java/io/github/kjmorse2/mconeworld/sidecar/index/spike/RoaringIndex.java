// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkEnvelope;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.CRC32C;
import org.jspecify.annotations.Nullable;
import org.roaringbitmap.longlong.PeekableLongIterator;
import org.roaringbitmap.longlong.Roaring64Bitmap;

/**
 * C3: Roaring bitmaps for membership and per-season sets, with C1's region blocks (segment layout)
 * as the side tables that hold timestamps and evidence.
 *
 * <p>Per dimension: {@code all}, {@code legacy}, {@code protected}, and per season {@code first}
 * and {@code active}. Bitmaps hold {@code key ^ Long.MIN_VALUE}, because Roaring iterates unsigned
 * order and {@link ChunkOrder} keys sort signed. Every commit clones the bitmaps (readers keep
 * theirs) and writes them all, in Roaring's portable format, as the catalog's attachment, so the
 * bitmaps and blocks commit atomically.
 */
public final class RoaringIndex extends RegionStoreIndex {
  private volatile Bitmaps current;

  private RoaringIndex(Path directory) throws ChunkIndexException {
    super(directory, Layout.SEGMENTS);
    Catalog committed = committedCatalog();
    Catalog.Attachment attachment = committed.attachment;
    if (attachment == null) {
      current = new Bitmaps();
    } else {
      try {
        byte[] bytes = Files.readAllBytes(directory.resolve(attachment.name()));
        CRC32C crc = new CRC32C();
        crc.update(bytes);
        if (bytes.length != attachment.length() || (int) crc.getValue() != attachment.crc()) {
          throw SpikeCodes.corrupt("bitmap file " + attachment.name() + " fails its checksum");
        }
        current = Bitmaps.read(bytes);
      } catch (IOException | RuntimeException e) {
        throw SpikeCodes.corrupt("cannot read bitmaps", e);
      }
    }
  }

  /** Opens (or creates) a C3 index in {@code directory}. */
  public static RoaringIndex open(Path directory) throws ChunkIndexException {
    return new RoaringIndex(directory);
  }

  // ---- bitmaps -------------------------------------------------------------------------------

  /** All bitmaps of one generation. Never modified after it is published. */
  static final class Bitmaps {
    final TreeMap<DimensionKey, Dimension> dimensions = new TreeMap<>();

    static final class Dimension {
      Roaring64Bitmap all = new Roaring64Bitmap();
      Roaring64Bitmap legacy = new Roaring64Bitmap();
      Roaring64Bitmap protectedChunks = new Roaring64Bitmap();
      final TreeMap<SeasonId, Roaring64Bitmap> first = new TreeMap<>();
      final TreeMap<SeasonId, Roaring64Bitmap> active = new TreeMap<>();

      Dimension copy() {
        Dimension d = new Dimension();
        d.all = all.clone();
        d.legacy = legacy.clone();
        d.protectedChunks = protectedChunks.clone();
        first.forEach((s, b) -> d.first.put(s, b.clone()));
        active.forEach((s, b) -> d.active.put(s, b.clone()));
        return d;
      }
    }

    byte[] write() throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(dimensions.size());
      for (Map.Entry<DimensionKey, Dimension> e : dimensions.entrySet()) {
        out.writeUTF(e.getKey().namespacedId());
        Dimension d = e.getValue();
        for (Roaring64Bitmap b : new Roaring64Bitmap[] {d.all, d.legacy, d.protectedChunks}) {
          b.runOptimize();
          b.serializePortable(out);
        }
        for (TreeMap<SeasonId, Roaring64Bitmap> sets : java.util.List.of(d.first, d.active)) {
          out.writeInt(sets.size());
          for (Map.Entry<SeasonId, Roaring64Bitmap> s : sets.entrySet()) {
            out.writeLong(s.getKey().value().getMostSignificantBits());
            out.writeLong(s.getKey().value().getLeastSignificantBits());
            s.getValue().runOptimize();
            s.getValue().serializePortable(out);
          }
        }
      }
      out.flush();
      return bytes.toByteArray();
    }

    static Bitmaps read(byte[] data) throws IOException {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
      Bitmaps bitmaps = new Bitmaps();
      int dims = in.readInt();
      for (int i = 0; i < dims; i++) {
        Dimension d = new Dimension();
        bitmaps.dimensions.put(DimensionKey.parse(in.readUTF()), d);
        d.all.deserializePortable(in);
        d.legacy.deserializePortable(in);
        d.protectedChunks.deserializePortable(in);
        for (TreeMap<SeasonId, Roaring64Bitmap> sets : java.util.List.of(d.first, d.active)) {
          int n = in.readInt();
          for (int s = 0; s < n; s++) {
            SeasonId season = SeasonId.of(new UUID(in.readLong(), in.readLong()));
            Roaring64Bitmap b = new Roaring64Bitmap();
            b.deserializePortable(in);
            sets.put(season, b);
          }
        }
      }
      if (in.read() != -1) {
        throw new IOException("trailing bytes in bitmap file");
      }
      return bitmaps;
    }
  }

  private static long flip(long key) {
    return key ^ Long.MIN_VALUE;
  }

  // ---- commit hooks --------------------------------------------------------------------------

  private @Nullable Bitmaps pending;

  @Override
  Catalog.@Nullable Attachment writeAttachment(
      Catalog next, Map<ChunkPosition, @Nullable ChunkProvenance> changes) throws IOException {
    Bitmaps base = current;
    Bitmaps updated = new Bitmaps();
    base.dimensions.forEach((key, d) -> updated.dimensions.put(key, d));
    TreeMap<DimensionKey, Bitmaps.Dimension> copied = new TreeMap<>();
    for (Map.Entry<ChunkPosition, @Nullable ChunkProvenance> change : changes.entrySet()) {
      DimensionKey dimension = change.getKey().dimension();
      Bitmaps.Dimension d = copied.get(dimension);
      if (d == null) {
        Bitmaps.Dimension old = base.dimensions.get(dimension);
        d = old == null ? new Bitmaps.Dimension() : old.copy();
        copied.put(dimension, d);
        updated.dimensions.put(dimension, d);
      }
      long key = flip(ChunkOrder.key(change.getKey()));
      d.all.removeLong(key);
      d.legacy.removeLong(key);
      d.protectedChunks.removeLong(key);
      d.first.values().forEach(b -> b.removeLong(key));
      d.active.values().forEach(b -> b.removeLong(key));
      ChunkProvenance record = change.getValue();
      if (record == null) {
        continue;
      }
      d.all.addLong(key);
      SeasonId first = record.firstObservation().knownSeason();
      if (first == null) {
        d.legacy.addLong(key);
      } else {
        d.first.computeIfAbsent(first, s -> new Roaring64Bitmap()).addLong(key);
      }
      for (SeasonActivity a : record.activity()) {
        d.active.computeIfAbsent(a.season(), s -> new Roaring64Bitmap()).addLong(key);
      }
      if (record.protection() == ChunkProtection.PROTECTED) {
        d.protectedChunks.addLong(key);
      }
    }
    byte[] bytes = updated.write();
    String name = "bitmaps-" + next.generation + ".bin";
    int crc = writeDurably(name, bytes);
    pending = updated;
    return new Catalog.Attachment(name, bytes.length, crc);
  }

  @Override
  void afterCommit(Catalog next) {
    Bitmaps updated = pending;
    if (updated != null) {
      current = updated;
      pending = null;
    }
    try {
      Files.deleteIfExists(directory.resolve("bitmaps-" + (next.generation - 1) + ".bin"));
    } catch (IOException ignored) {
      // A superseded bitmap file is unreferenced and harmless.
    }
  }

  // ---- reading -------------------------------------------------------------------------------

  @Override
  ChunkIndexReader newReader(Catalog snapshot) {
    return new BitmapReader(snapshot, current);
  }

  private final class BitmapReader extends Reader {
    private final Bitmaps bitmaps;

    BitmapReader(Catalog snapshot, Bitmaps bitmaps) {
      super(snapshot);
      this.bitmaps = bitmaps;
    }

    @Override
    public Optional<ChunkProvenance> find(ChunkPosition chunk) throws ChunkIndexException {
      requireUsable();
      Bitmaps.Dimension d = bitmaps.dimensions.get(chunk.dimension());
      if (d == null || !d.all.contains(flip(ChunkOrder.key(chunk)))) {
        return Optional.empty(); // answered without touching a block
      }
      return super.find(chunk);
    }

    @Override
    public boolean contains(ChunkPosition chunk) {
      requireUsable();
      Bitmaps.Dimension d = bitmaps.dimensions.get(chunk.dimension());
      return d != null && d.all.contains(flip(ChunkOrder.key(chunk)));
    }

    @Override
    public ChunkCursor scan(ChunkQuery query) {
      requireUsable();
      Roaring64Bitmap set = setFor(query);
      if (set == null) {
        return super.scan(query);
      }
      return new BitmapCursor(this, snapshot, query, set);
    }

    @Override
    public long count(ChunkQuery query) throws ChunkIndexException {
      requireUsable();
      Roaring64Bitmap set = setFor(query);
      if (set != null && query.area() == null && query.startAfter() == null && exact(query)) {
        return set.getLongCardinality();
      }
      return super.count(query);
    }

    @Override
    public ChunkEnvelope envelope(ChunkQuery query) throws ChunkIndexException {
      requireUsable();
      Roaring64Bitmap set = setFor(query);
      if (set == null || query.area() != null || query.startAfter() != null || !exact(query)) {
        return super.envelope(query);
      }
      long minX = Long.MAX_VALUE;
      long minZ = Long.MAX_VALUE;
      long maxX = Long.MIN_VALUE;
      long maxZ = Long.MIN_VALUE;
      PeekableLongIterator it = set.getLongIterator();
      while (it.hasNext()) {
        long key = flip(it.next());
        int x = ChunkOrder.x(key);
        int z = ChunkOrder.z(key);
        minX = Math.min(minX, x);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxZ = Math.max(maxZ, z);
      }
      if (minX == Long.MAX_VALUE) {
        return ChunkEnvelope.empty(query.dimension());
      }
      return new ChunkEnvelope.Bounded(
          new ChunkRectangle(query.dimension(), (int) minX, (int) minZ, (int) maxX, (int) maxZ));
    }

    /** Whether the bitmap from {@link #setFor} is exactly the query's season and protection set. */
    private boolean exact(ChunkQuery query) {
      return query.protection() == ProtectionFilter.ANY
          || query.seasons() instanceof SeasonFilter.Any;
    }

    /** The bitmap whose members are a superset of the query's results, or null to use C1. */
    private @Nullable Roaring64Bitmap setFor(ChunkQuery query) {
      Bitmaps.Dimension d = bitmaps.dimensions.get(query.dimension());
      if (d == null) {
        return new Roaring64Bitmap();
      }
      return switch (query.seasons()) {
        case SeasonFilter.Any any ->
            query.protection() == ProtectionFilter.PROTECTED ? d.protectedChunks : null;
        case SeasonFilter.Legacy legacy -> d.legacy;
        case SeasonFilter.FirstObservedIn f ->
            d.first.getOrDefault(f.season(), new Roaring64Bitmap());
        case SeasonFilter.ActiveIn a -> d.active.getOrDefault(a.season(), new Roaring64Bitmap());
        case SeasonFilter.TouchedBy t ->
            Roaring64Bitmap.or(
                d.first.getOrDefault(t.season(), new Roaring64Bitmap()),
                d.active.getOrDefault(t.season(), new Roaring64Bitmap()));
      };
    }
  }

  /** Iterates a bitmap in key order, reading each member's record from its (cached) block. */
  private final class BitmapCursor extends CursorBase {
    private final Catalog snapshot;
    private final ChunkQuery query;
    private final PeekableLongIterator keys;
    private final long last;

    BitmapCursor(ReaderBase reader, Catalog snapshot, ChunkQuery query, Roaring64Bitmap set) {
      super(reader);
      this.snapshot = snapshot;
      this.query = query;
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
      this.keys = set.getLongIteratorFrom(flip(first));
    }

    @Override
    @Nullable ChunkProvenance advance() throws ChunkIndexException {
      while (keys.hasNext()) {
        long key = flip(keys.next());
        if (key > last) {
          return null;
        }
        ChunkPosition chunk = ChunkOrder.position(query.dimension(), key);
        ChunkRectangle area = query.area();
        if (area != null && !area.contains(chunk)) {
          continue;
        }
        ChunkProvenance record = find(snapshot, chunk);
        if (record == null) {
          throw SpikeCodes.corrupt("bitmap names " + chunk + ", which has no record");
        }
        if (query.matches(record)) {
          return record;
        }
      }
      return null;
    }
  }
}
