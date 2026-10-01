// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.CRC32C;
import org.jspecify.annotations.Nullable;

/**
 * C1's catalog: an immutable snapshot of where every region block lives, plus per-region summaries
 * that let queries skip regions and answer counts and envelopes without reading blocks. A new
 * catalog is written for every commit and swapped in atomically; that swap is the commit point.
 *
 * <pre>
 *   "MCOC"  u8 version 0  i64 generation  varint nextFileId
 *   attachment: u8 present, then utf name, varint length, i32 crc
 *   varint dimension count, per dimension: utf key, varint region count, per region:
 *     i32 regionX  i32 regionZ  varint fileId  varLong offset  varint length
 *     128 bytes presence  varint count
 *     summary (see {@link Summary})
 *   i32 CRC-32C of every preceding byte
 * </pre>
 */
final class Catalog {
  private static final byte[] MAGIC = {'M', 'C', 'O', 'C'};

  final long generation;
  final long nextFileId;
  final @Nullable Attachment attachment;
  final Map<DimensionKey, NavigableMap<Long, Entry>> regions;

  Catalog(
      long generation,
      long nextFileId,
      @Nullable Attachment attachment,
      Map<DimensionKey, NavigableMap<Long, Entry>> regions) {
    this.generation = generation;
    this.nextFileId = nextFileId;
    this.attachment = attachment;
    this.regions = Collections.unmodifiableMap(regions);
  }

  static Catalog empty() {
    return new Catalog(0, 1, null, new TreeMap<>());
  }

  /** An extra file committed atomically with the catalog (C3's bitmaps). */
  record Attachment(String name, long length, int crc) {}

  /** Where one region block lives and what it summarizes. */
  static final class Entry {
    final int regionX;
    final int regionZ;
    final long fileId;
    final long offset;
    final int length;
    final long[] presence;
    final int count;
    final Summary summary;

    Entry(
        int regionX,
        int regionZ,
        long fileId,
        long offset,
        int length,
        long[] presence,
        int count,
        Summary summary) {
      this.regionX = regionX;
      this.regionZ = regionZ;
      this.fileId = fileId;
      this.offset = offset;
      this.length = length;
      this.presence = presence;
      this.count = count;
      this.summary = summary;
    }

    boolean present(int localIndex) {
      return (presence[localIndex >>> 6] & (1L << (localIndex & 63))) != 0;
    }
  }

  /**
   * Counts and local bounding boxes (packed {@code minX | minZ<<8 | maxX<<16 | maxZ<<24}, or {@code
   * -1} for none) per filter, so counts and envelopes of whole-dimension queries need no block
   * reads.
   */
  static final class Summary {
    int allBox = -1;
    int legacyCount;
    int legacyBox = -1;
    int protectedCount;
    int protectedBox = -1;
    final TreeMap<SeasonId, int[]> seasons =
        new TreeMap<>(); // first, firstBox, active, activeBox, touched

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
        SeasonId first = r.firstObservation().knownSeason();
        if (first != null) {
          int[] c = s.season(first);
          c[0]++;
          c[1] = grow(c[1], lx, lz);
        }
        for (SeasonActivity a : r.activity()) {
          int[] c = s.season(a.season());
          c[2]++;
          c[3] = grow(c[3], lx, lz);
        }
        for (SeasonId touched : touchedSeasons(r)) {
          s.season(touched)[4]++;
        }
      }
      return s;
    }

    private static java.util.Set<SeasonId> touchedSeasons(ChunkProvenance r) {
      java.util.Set<SeasonId> set = new java.util.TreeSet<>();
      SeasonId first = r.firstObservation().knownSeason();
      if (first != null) {
        set.add(first);
      }
      for (SeasonActivity a : r.activity()) {
        set.add(a.season());
      }
      return set;
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

    void write(ByteWriter out) {
      out.i32(allBox).varInt(legacyCount).i32(legacyBox).varInt(protectedCount).i32(protectedBox);
      out.varInt(seasons.size());
      for (Map.Entry<SeasonId, int[]> e : seasons.entrySet()) {
        UUID u = e.getKey().value();
        out.i64(u.getMostSignificantBits()).i64(u.getLeastSignificantBits());
        int[] c = e.getValue();
        out.varInt(c[0]).i32(c[1]).varInt(c[2]).i32(c[3]).varInt(c[4]);
      }
    }

    static Summary read(ByteReader in) throws ChunkIndexException {
      Summary s = new Summary();
      s.allBox = in.i32();
      s.legacyCount = in.varInt(1024);
      s.legacyBox = in.i32();
      s.protectedCount = in.varInt(1024);
      s.protectedBox = in.i32();
      int n = in.varInt(4096);
      for (int i = 0; i < n; i++) {
        SeasonId id;
        try {
          id = SeasonId.of(new UUID(in.i64(), in.i64()));
        } catch (RuntimeException e) {
          throw SpikeCodes.corrupt("catalog: invalid season id", e);
        }
        s.seasons.put(
            id, new int[] {in.varInt(1024), in.i32(), in.varInt(1024), in.i32(), in.varInt(1024)});
      }
      return s;
    }
  }

  byte[] encode() {
    ByteWriter out = new ByteWriter(1 << 16);
    out.bytes(MAGIC).u8(0).i64(generation).varLong(nextFileId);
    if (attachment == null) {
      out.u8(0);
    } else {
      out.u8(1).utf(attachment.name()).varLong(attachment.length()).i32(attachment.crc());
    }
    out.varInt(regions.size());
    for (Map.Entry<DimensionKey, NavigableMap<Long, Entry>> dimension : regions.entrySet()) {
      out.utf(dimension.getKey().namespacedId()).varInt(dimension.getValue().size());
      for (Entry e : dimension.getValue().values()) {
        out.i32(e.regionX).i32(e.regionZ).varLong(e.fileId).varLong(e.offset).varInt(e.length);
        for (long word : e.presence) {
          out.i64(word);
        }
        out.varInt(e.count);
        e.summary.write(out);
      }
    }
    CRC32C crc = new CRC32C();
    crc.update(out.array(), 0, out.size());
    out.i32((int) crc.getValue());
    return out.toByteArray();
  }

  static Catalog decode(byte[] bytes) throws ChunkIndexException {
    if (bytes.length < 8) {
      throw SpikeCodes.corrupt("catalog: too short");
    }
    CRC32C crc = new CRC32C();
    crc.update(bytes, 0, bytes.length - 4);
    if (new ByteReader(bytes, bytes.length - 4, 4, "catalog").i32() != (int) crc.getValue()) {
      throw SpikeCodes.corrupt("catalog: CRC-32C mismatch");
    }
    ByteReader in = new ByteReader(bytes, 0, bytes.length - 4, "catalog");
    for (byte b : MAGIC) {
      if (in.u8() != (b & 0xFF)) {
        throw SpikeCodes.corrupt("catalog: bad magic");
      }
    }
    if (in.u8() != 0) {
      throw SpikeCodes.corrupt("catalog: unknown version");
    }
    long generation = in.i64();
    long nextFileId = in.varLong();
    Attachment attachment = null;
    if (in.u8() == 1) {
      attachment = new Attachment(in.utf(255), in.varLong(), in.i32());
    }
    TreeMap<DimensionKey, NavigableMap<Long, Entry>> regions = new TreeMap<>();
    int dimensions = in.varInt(1024);
    for (int d = 0; d < dimensions; d++) {
      DimensionKey key;
      try {
        key = DimensionKey.parse(in.utf(DimensionKey.MAX_LENGTH * 4));
      } catch (RuntimeException e) {
        throw SpikeCodes.corrupt("catalog: invalid dimension", e);
      }
      TreeMap<Long, Entry> map = new TreeMap<>();
      int count = in.varInt(Integer.MAX_VALUE);
      for (int i = 0; i < count; i++) {
        int rx = in.i32();
        int rz = in.i32();
        long fileId = in.varLong();
        long offset = in.varLong();
        int length = in.varInt(64 * 1024 * 1024);
        long[] presence = new long[16];
        int bits = 0;
        for (int w = 0; w < 16; w++) {
          presence[w] = in.i64();
          bits += Long.bitCount(presence[w]);
        }
        int records = in.varInt(1024);
        if (bits != records) {
          throw SpikeCodes.corrupt("catalog: presence and count disagree");
        }
        Entry entry =
            new Entry(rx, rz, fileId, offset, length, presence, records, Summary.read(in));
        if (map.put(
                io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder.regionStart(rx, rz), entry)
            != null) {
          throw SpikeCodes.corrupt("catalog: duplicate region");
        }
      }
      regions.put(key, map);
    }
    if (in.remaining() != 0) {
      throw SpikeCodes.corrupt("catalog: trailing bytes");
    }
    return new Catalog(generation, nextFileId, attachment, regions);
  }
}
