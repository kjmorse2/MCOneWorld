// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The checkpoint (format §5): where every region's current block lives, plus derived per-region
 * summaries that let queries skip regions and answer whole-dimension counts and envelopes without
 * reading blocks. Immutable; a checkpoint writes a new one and swaps it in atomically.
 *
 * <pre>
 *   common header ("MCOC", version, index id, generation)
 *   varint next segment number
 *   varint dimension count; per dimension (sorted): utf8 key; varint region count; per region
 *   (ChunkOrder): i32 regionX, i32 regionZ, varint segment, varint offset, varint block length,
 *   varint header length, u32 header CRC-32C, 128 bytes presence, varint count, summary
 *   u32 CRC-32C of every preceding byte
 * </pre>
 */
final class Catalog {
  /** Smallest possible encoded region entry, used to bound counts before allocating. */
  private static final int MIN_ENTRY_BYTES = 4 + 4 + 1 + 1 + 1 + 1 + 4 + 128 + 1 + 4 * 3 + 2 + 1;

  final UUID indexId;
  final long generation;
  final long nextSegment;
  final boolean newerMinor;
  final Map<DimensionKey, NavigableMap<Long, Entry>> regions;

  Catalog(
      UUID indexId,
      long generation,
      long nextSegment,
      boolean newerMinor,
      Map<DimensionKey, NavigableMap<Long, Entry>> regions) {
    this.indexId = indexId;
    this.generation = generation;
    this.nextSegment = nextSegment;
    this.newerMinor = newerMinor;
    this.regions = Collections.unmodifiableMap(regions);
  }

  static Catalog empty(UUID indexId) {
    return new Catalog(indexId, 0, 1, false, new TreeMap<>());
  }

  /** The entry of the region containing {@code key}, or null. */
  @Nullable Entry entry(DimensionKey dimension, long key) {
    NavigableMap<Long, Entry> map = regions.get(dimension);
    return map == null
        ? null
        : map.get(ChunkOrder.regionStart(ChunkOrder.regionX(key), ChunkOrder.regionZ(key)));
  }

  /** Whether the checkpoint holds a record for chunk {@code key}: the presence bitmap alone. */
  boolean present(DimensionKey dimension, long key) {
    Entry e = entry(dimension, key);
    return e != null && e.present(ChunkOrder.localIndex(key));
  }

  long records() {
    long n = 0;
    for (NavigableMap<Long, Entry> map : regions.values()) {
      for (Entry e : map.values()) {
        n += e.count;
      }
    }
    return n;
  }

  /** Where one region's block lives and what it summarizes. */
  static final class Entry {
    final int regionX;
    final int regionZ;
    final long segment;
    final long offset;
    final int length;
    final int headerLength;
    final int headerCrc;
    private final long[] presence;
    final int count;
    final Summary summary;

    Entry(
        int regionX,
        int regionZ,
        long segment,
        long offset,
        int length,
        int headerLength,
        int headerCrc,
        long[] presence,
        int count,
        Summary summary) {
      this.regionX = regionX;
      this.regionZ = regionZ;
      this.segment = segment;
      this.offset = offset;
      this.length = length;
      this.headerLength = headerLength;
      this.headerCrc = headerCrc;
      this.presence = presence;
      this.count = count;
      this.summary = summary;
    }

    boolean present(int localIndex) {
      return (presence[localIndex >>> 6] & (1L << (localIndex & 63))) != 0;
    }

    boolean samePresence(long[] other) {
      return Arrays.equals(presence, other);
    }

    /** The same block at another location (compaction copies blocks byte for byte). */
    Entry movedTo(long newSegment, long newOffset) {
      return new Entry(
          regionX,
          regionZ,
          newSegment,
          newOffset,
          length,
          headerLength,
          headerCrc,
          presence,
          count,
          summary);
    }

    static Entry of(
        int regionX,
        int regionZ,
        long segment,
        long offset,
        BlockCodec.Encoded block,
        @Nullable ChunkProvenance[] records) {
      long[] presence = new long[16];
      int count = 0;
      for (int i = 0; i < 1024; i++) {
        if (records[i] != null) {
          presence[i >>> 6] |= 1L << (i & 63);
          count++;
        }
      }
      return new Entry(
          regionX,
          regionZ,
          segment,
          offset,
          block.bytes().length,
          block.headerLength(),
          block.headerCrc(),
          presence,
          count,
          Summary.of(records));
    }
  }

  /**
   * Counts and local bounding boxes per filter. A box packs local coordinates as {@code minX | minZ
   * << 8 | maxX << 16 | maxZ << 24}, or is -1 when empty. Derived data: verification recomputes it
   * from the block, and a mismatch is corruption.
   */
  static final class Summary {
    int allBox = -1;
    int legacyCount;
    int legacyBox = -1;
    int protectedCount;
    int protectedBox = -1;

    /** Per season: first count, first box, active count, active box, touched count. */
    final TreeMap<SeasonId, int[]> seasons = new TreeMap<>();

    static Summary of(@Nullable ChunkProvenance[] records) {
      Summary s = new Summary();
      for (int i = 0; i < 1024; i++) {
        ChunkProvenance r = records[i];
        if (r == null) {
          continue;
        }
        int lx = i & 31;
        int lz = i >>> 5;
        s.allBox = grow(s.allBox, lx, lz);
        if (r.isLegacy()) {
          s.legacyCount++;
          s.legacyBox = grow(s.legacyBox, lx, lz);
        }
        if (r.protection() == ChunkProtection.PROTECTED) {
          s.protectedCount++;
          s.protectedBox = grow(s.protectedBox, lx, lz);
        }
        TreeSet<SeasonId> touched = new TreeSet<>();
        SeasonId first = r.firstObservation().knownSeason();
        if (first != null) {
          int[] c = s.season(first);
          c[0]++;
          c[1] = grow(c[1], lx, lz);
          touched.add(first);
        }
        for (SeasonActivity a : r.activity()) {
          int[] c = s.season(a.season());
          c[2]++;
          c[3] = grow(c[3], lx, lz);
          touched.add(a.season());
        }
        for (SeasonId t : touched) {
          s.season(t)[4]++;
        }
      }
      return s;
    }

    private int[] season(SeasonId id) {
      return seasons.computeIfAbsent(id, k -> new int[] {0, -1, 0, -1, 0});
    }

    static int grow(int box, int lx, int lz) {
      if (box == -1) {
        return lx | lz << 8 | lx << 16 | lz << 24;
      }
      return Math.min(box & 0xFF, lx)
          | Math.min((box >>> 8) & 0xFF, lz) << 8
          | Math.max((box >>> 16) & 0xFF, lx) << 16
          | Math.max(box >>> 24, lz) << 24;
    }

    void write(ByteSink out) {
      out.i32(allBox).varInt(legacyCount).i32(legacyBox).varInt(protectedCount).i32(protectedBox);
      out.varInt(seasons.size());
      for (Map.Entry<SeasonId, int[]> e : seasons.entrySet()) {
        UUID u = e.getKey().value();
        out.i64(u.getMostSignificantBits()).i64(u.getLeastSignificantBits());
        int[] c = e.getValue();
        out.varInt(c[0]).i32(c[1]).varInt(c[2]).i32(c[3]).varInt(c[4]);
      }
    }

    byte[] encoded() {
      ByteSink out = new ByteSink(64);
      write(out);
      return out.toByteArray();
    }

    static Summary read(ByteSource in) throws ChunkIndexException {
      Summary s = new Summary();
      s.allBox = box(in);
      s.legacyCount = in.varInt(1024);
      s.legacyBox = box(in);
      s.protectedCount = in.varInt(1024);
      s.protectedBox = box(in);
      int n = in.count(IndexFormat.MAX_SEASONS, 16 + 3 + 8);
      SeasonId previous = null;
      for (int i = 0; i < n; i++) {
        SeasonId id;
        try {
          id = SeasonId.of(new UUID(in.i64(), in.i64()));
        } catch (RuntimeException e) {
          throw in.corrupt("invalid season id in a summary", e);
        }
        if (previous != null && previous.compareTo(id) >= 0) {
          throw in.corrupt("summary seasons are not sorted and unique");
        }
        previous = id;
        s.seasons.put(
            id, new int[] {in.varInt(1024), box(in), in.varInt(1024), box(in), in.varInt(1024)});
      }
      return s;
    }

    private static int box(ByteSource in) throws ChunkIndexException {
      int box = in.i32();
      if (box != -1
          && ((box & 0xFF) > 31
              || ((box >>> 8) & 0xFF) > 31
              || ((box >>> 16) & 0xFF) > 31
              || (box >>> 24) > 31)) {
        throw in.corrupt("bounding box out of range");
      }
      return box;
    }
  }

  // ---- codec ---------------------------------------------------------------------------------

  byte[] encode() {
    ByteSink out = new ByteSink(1 << 16);
    IndexFormat.writeHeader(out, IndexFormat.CATALOG_MAGIC, indexId, generation);
    out.varLong(nextSegment);
    out.varInt(regions.size());
    for (Map.Entry<DimensionKey, NavigableMap<Long, Entry>> dimension :
        new TreeMap<>(regions).entrySet()) {
      out.utf(dimension.getKey().namespacedId()).varInt(dimension.getValue().size());
      for (Entry e : dimension.getValue().values()) {
        out.i32(e.regionX).i32(e.regionZ).varLong(e.segment).varLong(e.offset).varInt(e.length);
        out.varInt(e.headerLength).i32(e.headerCrc);
        for (long word : e.presence) {
          out.i64(word);
        }
        out.varInt(e.count);
        e.summary.write(out);
      }
    }
    out.i32(IndexFormat.crc(out.array(), 0, out.size()));
    return out.toByteArray();
  }

  /**
   * Decodes and verifies a catalog: header, whole-file checksum, and every bound.
   *
   * @throws ChunkIndexException {@code CATALOG_CORRUPT}, {@code HEADER_CORRUPT}, or {@code
   *     UNSUPPORTED_VERSION}
   */
  static Catalog decode(byte[] bytes) throws ChunkIndexException {
    String scope = "catalog";
    IndexFormat.FileHeader header =
        IndexFormat.readHeader(bytes, 0, bytes.length, IndexFormat.CATALOG_MAGIC, scope);
    if (bytes.length < IndexFormat.HEADER_BYTES + 4
        || IndexFormat.crc(bytes, 0, bytes.length - 4)
            != new ByteSource(
                    bytes, bytes.length - 4, 4, ChunkIndexIssueCodes.CATALOG_CORRUPT, scope)
                .i32()) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.CATALOG_CORRUPT, "catalog checksum mismatch", scope, null);
    }
    ByteSource in =
        new ByteSource(
            bytes,
            IndexFormat.HEADER_BYTES,
            bytes.length - 4 - IndexFormat.HEADER_BYTES,
            ChunkIndexIssueCodes.CATALOG_CORRUPT,
            scope);
    long nextSegment = in.varLong(Long.MAX_VALUE / 2);
    int dimensions = in.count(IndexFormat.MAX_DIMENSIONS, 3);
    TreeMap<DimensionKey, NavigableMap<Long, Entry>> regions = new TreeMap<>();
    DimensionKey previousDimension = null;
    for (int d = 0; d < dimensions; d++) {
      DimensionKey key;
      try {
        key = DimensionKey.parse(in.utf(DimensionKey.MAX_LENGTH * 4));
      } catch (RuntimeException e) {
        throw in.corrupt("invalid dimension key", e);
      }
      if (previousDimension != null && previousDimension.compareTo(key) >= 0) {
        throw in.corrupt("dimensions are not sorted and unique");
      }
      previousDimension = key;
      int count = in.count(Integer.MAX_VALUE, MIN_ENTRY_BYTES);
      TreeMap<Long, Entry> map = new TreeMap<>();
      long previousKey = 0;
      for (int i = 0; i < count; i++) {
        int rx = in.i32();
        int rz = in.i32();
        if (rx < -(1 << 26) || rx >= 1 << 26 || rz < -(1 << 26) || rz >= 1 << 26) {
          throw in.corrupt("region coordinates out of range");
        }
        long segment = in.varLong(nextSegment - 1);
        if (segment < 1) {
          throw in.corrupt("segment number 0");
        }
        long offset = in.varLong(Long.MAX_VALUE / 2);
        if (offset < IndexFormat.HEADER_BYTES) {
          throw in.corrupt("block offset inside the segment header");
        }
        int length = in.varInt(IndexFormat.MAX_BLOCK_BYTES);
        int headerLength = in.varInt(IndexFormat.MAX_BLOCK_HEADER_BYTES);
        if ((long) headerLength + 12 > length) {
          throw in.corrupt("block header longer than its block");
        }
        int headerCrc = in.i32();
        long[] presence = new long[16];
        int bits = 0;
        for (int w = 0; w < 16; w++) {
          presence[w] = in.i64();
          bits += Long.bitCount(presence[w]);
        }
        int records = in.varInt(1024);
        if (bits != records || records == 0) {
          throw in.corrupt("region r." + rx + "." + rz + ": presence and count disagree");
        }
        Entry entry =
            new Entry(
                rx,
                rz,
                segment,
                offset,
                length,
                headerLength,
                headerCrc,
                presence,
                records,
                Summary.read(in));
        long regionKey = ChunkOrder.regionStart(rx, rz);
        if (i > 0 && regionKey <= previousKey) {
          throw in.corrupt("regions are not in order or repeat");
        }
        previousKey = regionKey;
        map.put(regionKey, entry);
      }
      regions.put(key, map);
    }
    in.requireEnd();
    return new Catalog(
        header.indexId(), header.number(), nextSegment, header.newerMinor(), regions);
  }
}
