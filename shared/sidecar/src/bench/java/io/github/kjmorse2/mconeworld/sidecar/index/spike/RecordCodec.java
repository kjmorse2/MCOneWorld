// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import java.util.zip.CRC32C;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;

/**
 * The spike's binary record encoding, shared by the region-block candidates (C1, C3) and MVStore
 * values (C4), so that every candidate stores the same bytes per record and differences come from
 * the storage engine.
 *
 * <p>One record:
 *
 * <pre>
 *   u8      flags: bit 0 legacy, bit 1 protected
 *   varint  first season index          (absent when legacy)
 *   zigzag  first observedAt seconds - base;  varint nanos;  u8 evidence
 *   varint  activity count, then per entry:
 *           varint season index;  zigzag seconds - base;  varint nanos;  u8 evidence
 * </pre>
 *
 * <p>{@code evidence} is an {@link EvidenceCodes} byte. A region block wraps the records of one 32
 * × 32 region:
 *
 * <pre>
 *   "MCOB"  u8 version 0  utf dimension  i32 regionX  i32 regionZ
 *   varint season count, then 16 bytes per season (the block's season dictionary, sorted)
 *   zigzag base second
 *   128 bytes presence bitmap (bit i = local index i)
 *   varint raw length  varint deflated length  deflated records in local-index order
 *   i32 CRC-32C of every preceding byte
 * </pre>
 */
final class RecordCodec {
  private static final byte[] MAGIC = {'M', 'C', 'O', 'B'};
  private static final int MAX_ACTIVITY = 4096;

  private RecordCodec() {}

  /** A decoded region: up to 1024 records indexed by local index. Treat as immutable. */
  static final class Block {
    final DimensionKey dimension;
    final int regionX;
    final int regionZ;
    private final @Nullable ChunkProvenance[] records;
    final int count;

    Block(
        DimensionKey dimension,
        int regionX,
        int regionZ,
        @Nullable ChunkProvenance[] records,
        int count) {
      this.dimension = dimension;
      this.regionX = regionX;
      this.regionZ = regionZ;
      this.records = records;
      this.count = count;
    }

    @Nullable ChunkProvenance at(int localIndex) {
      return records[localIndex];
    }

    /** A mutable copy of the records, for building the next version of this region. */
    @Nullable ChunkProvenance[] copyRecords() {
      return records.clone();
    }
  }

  // ---- single records ------------------------------------------------------------------------

  static void writeRecord(
      ByteWriter out, ChunkProvenance r, ToIntFunction<SeasonId> seasonIndex, long baseSecond) {
    FirstObservation first = r.firstObservation();
    int flags = (r.isLegacy() ? 1 : 0) | (r.protection() == ChunkProtection.PROTECTED ? 2 : 0);
    out.u8(flags);
    SeasonId firstSeason = first.knownSeason();
    if (firstSeason != null) {
      out.varInt(seasonIndex.applyAsInt(firstSeason));
    }
    writeInstant(out, first.observedAt(), baseSecond);
    out.u8(EvidenceCodes.encode(first.evidence()));
    out.varInt(r.activity().size());
    for (SeasonActivity a : r.activity()) {
      out.varInt(seasonIndex.applyAsInt(a.season()));
      writeInstant(out, a.lastObservedAt(), baseSecond);
      out.u8(EvidenceCodes.encode(a.evidence()));
    }
  }

  static ChunkProvenance readRecord(
      ByteReader in, ChunkPosition chunk, IntFunction<@Nullable SeasonId> seasons, long baseSecond)
      throws ChunkIndexException {
    int flags = in.u8();
    if ((flags & ~3) != 0) {
      throw SpikeCodes.corrupt("record flags " + flags + " for " + chunk);
    }
    SeasonId firstSeason = (flags & 1) == 0 ? season(seasons, in.varInt(Integer.MAX_VALUE)) : null;
    Instant firstAt = readInstant(in, baseSecond);
    ProvenanceEvidence firstEvidence = EvidenceCodes.decode(in.u8());
    FirstObservation first =
        firstSeason == null
            ? new FirstObservation.Legacy(firstAt, firstEvidence)
            : new FirstObservation.InSeason(firstSeason, firstAt, firstEvidence);
    int count = in.varInt(MAX_ACTIVITY);
    List<SeasonActivity> activity = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      SeasonId season = season(seasons, in.varInt(Integer.MAX_VALUE));
      Instant at = readInstant(in, baseSecond);
      activity.add(new SeasonActivity(season, at, EvidenceCodes.decode(in.u8())));
    }
    try {
      return new ChunkProvenance(
          chunk,
          first,
          activity,
          (flags & 2) != 0 ? ChunkProtection.PROTECTED : ChunkProtection.NONE);
    } catch (RuntimeException e) {
      throw SpikeCodes.corrupt("invalid record for " + chunk, e);
    }
  }

  // ---- region blocks -------------------------------------------------------------------------

  /** Encodes the non-null entries of {@code records} (indexed by local index). */
  static byte[] encodeBlock(
      DimensionKey dimension, int regionX, int regionZ, @Nullable ChunkProvenance[] records) {
    TreeMap<SeasonId, Integer> dictionary = new TreeMap<>();
    long base = Long.MAX_VALUE;
    byte[] presence = new byte[128];
    for (int i = 0; i < 1024; i++) {
      ChunkProvenance r = records[i];
      if (r == null) {
        continue;
      }
      presence[i >>> 3] |= (byte) (1 << (i & 7));
      SeasonId s = r.firstObservation().knownSeason();
      if (s != null) {
        dictionary.put(s, 0);
      }
      base = Math.min(base, r.firstObservation().observedAt().getEpochSecond());
      for (SeasonActivity a : r.activity()) {
        dictionary.put(a.season(), 0);
        base = Math.min(base, a.lastObservedAt().getEpochSecond());
      }
    }
    if (base == Long.MAX_VALUE) {
      base = 0;
    }
    int next = 0;
    for (var entry : dictionary.entrySet()) {
      entry.setValue(next++);
    }
    ByteWriter raw = new ByteWriter(16 * 1024);
    for (int i = 0; i < 1024; i++) {
      ChunkProvenance r = records[i];
      if (r != null) {
        writeRecord(raw, r, dictionary::get, base);
      }
    }
    byte[] deflated = deflate(raw.array(), raw.size());

    ByteWriter out = new ByteWriter(deflated.length + 256 + dictionary.size() * 16);
    out.bytes(MAGIC).u8(0).utf(dimension.namespacedId()).i32(regionX).i32(regionZ);
    out.varInt(dictionary.size());
    for (SeasonId s : dictionary.keySet()) {
      out.i64(s.value().getMostSignificantBits()).i64(s.value().getLeastSignificantBits());
    }
    out.zigZag(base).bytes(presence).varInt(raw.size()).varInt(deflated.length).bytes(deflated);
    CRC32C crc = new CRC32C();
    crc.update(out.array(), 0, out.size());
    out.i32((int) crc.getValue());
    return out.toByteArray();
  }

  /**
   * Decodes and verifies a block, which must describe the expected region.
   *
   * @throws ChunkIndexException with the spike's corruption code on any inconsistency
   */
  static Block decodeBlock(byte[] block, DimensionKey dimension, int regionX, int regionZ)
      throws ChunkIndexException {
    String what = "block " + dimension + " r." + regionX + "." + regionZ;
    if (block.length < 4 + 1 + 4) {
      throw SpikeCodes.corrupt(what + ": too short");
    }
    CRC32C crc = new CRC32C();
    crc.update(block, 0, block.length - 4);
    ByteReader trailer = new ByteReader(block, block.length - 4, 4, what);
    if (trailer.i32() != (int) crc.getValue()) {
      throw SpikeCodes.corrupt(what + ": CRC-32C mismatch");
    }
    ByteReader in = new ByteReader(block, 0, block.length - 4, what);
    for (byte b : MAGIC) {
      if (in.u8() != (b & 0xFF)) {
        throw SpikeCodes.corrupt(what + ": bad magic");
      }
    }
    if (in.u8() != 0) {
      throw SpikeCodes.corrupt(what + ": unknown version");
    }
    if (!in.utf(256).equals(dimension.namespacedId())
        || in.i32() != regionX
        || in.i32() != regionZ) {
      throw SpikeCodes.corrupt(what + ": header names another region");
    }
    int seasonCount = in.varInt(4096);
    SeasonId[] seasons = new SeasonId[seasonCount];
    for (int i = 0; i < seasonCount; i++) {
      try {
        seasons[i] = SeasonId.of(new UUID(in.i64(), in.i64()));
      } catch (RuntimeException e) {
        throw SpikeCodes.corrupt(what + ": invalid season id", e);
      }
    }
    long base = in.zigZag();
    byte[] presence = in.bytes(128);
    int rawLength = in.varInt(64 * 1024 * 1024);
    int deflatedLength = in.varInt(in.remaining());
    byte[] raw = inflate(in.bytes(deflatedLength), rawLength, what);
    ByteReader records = new ByteReader(raw, 0, raw.length, what);
    @Nullable ChunkProvenance[] out = new ChunkProvenance[1024];
    int count = 0;
    int baseX = regionX << 5;
    int baseZ = regionZ << 5;
    for (int i = 0; i < 1024; i++) {
      if ((presence[i >>> 3] & (1 << (i & 7))) != 0) {
        ChunkPosition chunk = new ChunkPosition(dimension, baseX | (i & 31), baseZ | (i >>> 5));
        out[i] = readRecord(records, chunk, k -> k < seasons.length ? seasons[k] : null, base);
        count++;
      }
    }
    if (records.remaining() != 0) {
      throw SpikeCodes.corrupt(what + ": trailing record bytes");
    }
    return new Block(dimension, regionX, regionZ, out, count);
  }

  // ---- helpers -------------------------------------------------------------------------------

  private static SeasonId season(IntFunction<@Nullable SeasonId> seasons, int index)
      throws ChunkIndexException {
    SeasonId s = seasons.apply(index);
    if (s == null) {
      throw SpikeCodes.corrupt("season index " + index + " not in dictionary");
    }
    return s;
  }

  private static void writeInstant(ByteWriter out, Instant at, long baseSecond) {
    out.zigZag(at.getEpochSecond() - baseSecond).varInt(at.getNano());
  }

  private static Instant readInstant(ByteReader in, long baseSecond) throws ChunkIndexException {
    long seconds = in.zigZag() + baseSecond;
    int nanos = in.varInt(999_999_999);
    try {
      return Instant.ofEpochSecond(seconds, nanos);
    } catch (RuntimeException e) {
      throw SpikeCodes.corrupt("instant out of range", e);
    }
  }

  static byte[] deflate(byte[] data, int length) {
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    try {
      deflater.setInput(data, 0, length);
      deflater.finish();
      ByteWriter out = new ByteWriter(length / 3 + 64);
      byte[] chunk = new byte[16 * 1024];
      while (!deflater.finished()) {
        int n = deflater.deflate(chunk);
        out.bytes(chunk, 0, n);
      }
      return out.toByteArray();
    } finally {
      deflater.end();
    }
  }

  static byte[] inflate(byte[] data, int rawLength, String what) throws ChunkIndexException {
    Inflater inflater = new Inflater(true);
    try {
      inflater.setInput(data);
      byte[] out = new byte[rawLength];
      int n = 0;
      while (n < rawLength) {
        int read = inflater.inflate(out, n, rawLength - n);
        if (read == 0
            && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
          break;
        }
        n += read;
      }
      if (n == rawLength && !inflater.finished() && inflater.inflate(new byte[1]) != 0) {
        throw SpikeCodes.corrupt(what + ": deflate stream longer than recorded");
      }
      if (n != rawLength || !inflater.finished()) {
        throw SpikeCodes.corrupt(what + ": deflate stream length mismatch");
      }
      return out;
    } catch (DataFormatException e) {
      throw SpikeCodes.corrupt(what + ": invalid deflate stream", e);
    } finally {
      inflater.end();
    }
  }
}
