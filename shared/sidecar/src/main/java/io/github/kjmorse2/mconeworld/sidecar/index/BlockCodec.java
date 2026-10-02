// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Region blocks (format §4): the records of one 32 × 32 region, compressed in <b>units</b> of
 * {@code rowsPerUnit} rows so that a lookup inflates one unit, not the whole region.
 *
 * <pre>
 *   "MCOB"  u32 header length H  header (H bytes)  u32 CRC-32C of the preceding 8 + H bytes
 *   unit 0 stored bytes, unit 1 stored bytes, ...       (raw DEFLATE; empty units store nothing)
 *
 *   header: utf8 dimension; i32 regionX; i32 regionZ
 *           varint S, S x 16-byte season ids (sorted, unique)
 *           zigzag base epoch second
 *           128 bytes presence bitmap (bit i = local index i = localZ * 32 + localX)
 *           u8 rowsPerUnit (1, 2, 4, 8, 16, or 32)
 *           per unit (32 / rowsPerUnit of them): varint raw length, varint stored length,
 *                                                u32 CRC-32C of the stored bytes
 * </pre>
 *
 * <p>Unit {@code u} holds, in local-index order, the records of local indexes {@code [u * rows *
 * 32, (u + 1) * rows * 32)} that are present. Encoding is deterministic: the same records always
 * produce the same bytes.
 */
final class BlockCodec {
  private BlockCodec() {}

  /** An encoded block plus what the catalog records about it. */
  static final class Encoded {
    private final byte[] bytes;
    private final int headerLength;
    private final int headerCrc;

    Encoded(byte[] bytes, int headerLength, int headerCrc) {
      this.bytes = bytes;
      this.headerLength = headerLength;
      this.headerCrc = headerCrc;
    }

    byte[] bytes() {
      return bytes;
    }

    int headerLength() {
      return headerLength;
    }

    int headerCrc() {
      return headerCrc;
    }
  }

  /** A verified block header. Immutable once built. */
  static final class Header {
    private final DimensionKey dimension;
    private final int regionX;
    private final int regionZ;
    private final SeasonId[] seasons;
    private final long baseSecond;
    private final long[] presence;
    private final int rowsPerUnit;
    private final int[] rawLengths;
    private final int[] storedLengths;
    private final int[] crcs;
    private final int[] unitOffsets; // from the start of the block
    private final int blockLength;

    Header(
        DimensionKey dimension,
        int regionX,
        int regionZ,
        SeasonId[] seasons,
        long baseSecond,
        long[] presence,
        int rowsPerUnit,
        int[] rawLengths,
        int[] storedLengths,
        int[] crcs,
        int[] unitOffsets,
        int blockLength) {
      this.dimension = dimension;
      this.regionX = regionX;
      this.regionZ = regionZ;
      this.seasons = seasons;
      this.baseSecond = baseSecond;
      this.presence = presence;
      this.rowsPerUnit = rowsPerUnit;
      this.rawLengths = rawLengths;
      this.storedLengths = storedLengths;
      this.crcs = crcs;
      this.unitOffsets = unitOffsets;
      this.blockLength = blockLength;
    }

    DimensionKey dimension() {
      return dimension;
    }

    int regionX() {
      return regionX;
    }

    int regionZ() {
      return regionZ;
    }

    SeasonId[] seasons() {
      return seasons;
    }

    long baseSecond() {
      return baseSecond;
    }

    int rowsPerUnit() {
      return rowsPerUnit;
    }

    int rawLength(int unit) {
      return rawLengths[unit];
    }

    int storedLength(int unit) {
      return storedLengths[unit];
    }

    int crc(int unit) {
      return crcs[unit];
    }

    /** Offset of a unit's stored bytes from the start of the block. */
    int unitOffset(int unit) {
      return unitOffsets[unit];
    }

    int blockLength() {
      return blockLength;
    }

    int units() {
      return rawLengths.length;
    }

    /** The unit that holds local index {@code local}. */
    int unitOf(int local) {
      return local / (rowsPerUnit * 32);
    }

    boolean present(int local) {
      return (presence[local >>> 6] & (1L << (local & 63))) != 0;
    }

    /** The presence bitmap's words (a copy). */
    long[] presenceWords() {
      return presence.clone();
    }

    int count() {
      int n = 0;
      for (long word : presence) {
        n += Long.bitCount(word);
      }
      return n;
    }

    /** Approximate heap held while cached. */
    long retainedBytes() {
      return 256L + 48L * seasons.length + 16L * rawLengths.length;
    }
  }

  /** A decoded unit: its inflated records and where each starts. Immutable once built. */
  static final class Unit {
    private final Header header;
    private final int unit;
    private final byte[] raw;
    private final int[] offsets; // per local index of the unit, -1 where absent
    private final String scope;

    Unit(Header header, int unit, byte[] raw, int[] offsets, String scope) {
      this.header = header;
      this.unit = unit;
      this.raw = raw;
      this.offsets = offsets;
      this.scope = scope;
    }

    /** The record at local index {@code local} (which must be in this unit), or null. */
    @Nullable ChunkProvenance record(int local) throws ChunkIndexException {
      int offset = offsets[local - unit * header.rowsPerUnit() * 32];
      if (offset < 0) {
        return null;
      }
      ChunkPosition chunk =
          new ChunkPosition(
              header.dimension(),
              (header.regionX() << 5) | (local & 31),
              (header.regionZ() << 5) | (local >>> 5));
      ByteSource in =
          new ByteSource(
              raw, offset, raw.length - offset, ChunkIndexIssueCodes.BLOCK_CORRUPT, scope);
      return RecordCodec.read(in, chunk, header.seasons(), header.baseSecond());
    }

    long retainedBytes() {
      return 96L + raw.length + 4L * offsets.length;
    }
  }

  // ---- encoding ------------------------------------------------------------------------------

  static Encoded encode(
      DimensionKey dimension,
      int regionX,
      int regionZ,
      @Nullable ChunkProvenance[] records,
      int rowsPerUnit) {
    TreeMap<SeasonId, Integer> dictionary = new TreeMap<>();
    long base = Long.MAX_VALUE;
    long[] presence = new long[16];
    for (int i = 0; i < 1024; i++) {
      ChunkProvenance r = records[i];
      if (r == null) {
        continue;
      }
      presence[i >>> 6] |= 1L << (i & 63);
      SeasonId first = r.firstObservation().knownSeason();
      if (first != null) {
        dictionary.put(first, 0);
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
    int units = 32 / rowsPerUnit;
    int perUnit = rowsPerUnit * 32;
    byte[][] stored = new byte[units][];
    int[] rawLengths = new int[units];
    ByteSink raw = new ByteSink(4096);
    for (int u = 0; u < units; u++) {
      raw.reset();
      for (int i = u * perUnit; i < (u + 1) * perUnit; i++) {
        ChunkProvenance r = records[i];
        if (r != null) {
          RecordCodec.write(raw, r, dictionary::get, base);
        }
      }
      rawLengths[u] = raw.size();
      stored[u] = raw.size() == 0 ? new byte[0] : IndexFormat.deflate(raw.array(), 0, raw.size());
    }
    ByteSink header = new ByteSink(256 + 16 * dictionary.size() + 12 * units);
    header.utf(dimension.namespacedId()).i32(regionX).i32(regionZ);
    IndexFormat.writeSeasons(header, dictionary.keySet());
    header.zigZag(base);
    for (long word : presence) {
      header.i64(word);
    }
    header.u8(rowsPerUnit);
    for (int u = 0; u < units; u++) {
      header
          .varInt(rawLengths[u])
          .varInt(stored[u].length)
          .i32(stored[u].length == 0 ? 0 : IndexFormat.crc(stored[u], 0, stored[u].length));
    }
    ByteSink out = new ByteSink(header.size() + 64);
    out.bytes(IndexFormat.BLOCK_MAGIC).i32(header.size()).bytes(header.array(), 0, header.size());
    int headerCrc = IndexFormat.crc(out.array(), 0, out.size());
    out.i32(headerCrc);
    for (byte[] unit : stored) {
      out.bytes(unit);
    }
    return new Encoded(out.toByteArray(), header.size(), headerCrc);
  }

  // ---- decoding ------------------------------------------------------------------------------

  /**
   * Decodes and verifies a block header that starts at {@code offset} of {@code bytes}, which hold
   * at least the header and its checksum. The header must match what the catalog recorded: its
   * length, its checksum, its region, and the block's total length.
   */
  static Header readHeader(
      byte[] bytes,
      int offset,
      DimensionKey dimension,
      int regionX,
      int regionZ,
      int headerLength,
      int headerCrc,
      int blockLength,
      String scope)
      throws ChunkIndexException {
    ByteSource frame =
        new ByteSource(
            bytes,
            offset,
            Math.min(bytes.length - offset, 12 + headerLength),
            ChunkIndexIssueCodes.BLOCK_CORRUPT,
            scope);
    for (byte b : IndexFormat.BLOCK_MAGIC) {
      if (frame.u8() != (b & 0xFF)) {
        throw frame.corrupt("bad block magic");
      }
    }
    if (frame.i32() != headerLength) {
      throw frame.corrupt("header length differs from the catalog");
    }
    frame.skip(headerLength);
    int stored = frame.i32();
    if (stored != IndexFormat.crc(bytes, offset, 8 + headerLength)) {
      throw frame.corrupt("header checksum mismatch");
    }
    if (stored != headerCrc) {
      throw frame.corrupt("header checksum differs from the catalog's copy");
    }
    ByteSource in =
        new ByteSource(bytes, offset + 8, headerLength, ChunkIndexIssueCodes.BLOCK_CORRUPT, scope);
    String dimensionText = in.utf(DimensionKey.MAX_LENGTH * 4);
    if (!dimensionText.equals(dimension.namespacedId())
        || in.i32() != regionX
        || in.i32() != regionZ) {
      throw in.corrupt("block describes another region than the catalog");
    }
    SeasonId[] seasons = IndexFormat.readSeasons(in);
    long base = in.zigZag();
    long[] presence = new long[16];
    for (int w = 0; w < 16; w++) {
      presence[w] = in.i64();
    }
    int rows = in.u8();
    if (Integer.bitCount(rows) != 1 || rows > 32) {
      throw in.corrupt("rows per unit " + rows);
    }
    int units = 32 / rows;
    int[] rawLengths = new int[units];
    int[] storedLengths = new int[units];
    int[] crcs = new int[units];
    int[] unitOffsets = new int[units];
    long position = 12L + headerLength;
    for (int u = 0; u < units; u++) {
      rawLengths[u] = in.varInt(IndexFormat.MAX_UNIT_RAW_BYTES);
      storedLengths[u] = in.varInt(IndexFormat.MAX_BLOCK_BYTES);
      crcs[u] = in.i32();
      if ((rawLengths[u] == 0) != (storedLengths[u] == 0)) {
        throw in.corrupt("unit " + u + " has inconsistent lengths");
      }
      unitOffsets[u] = (int) position;
      position += storedLengths[u];
    }
    in.requireEnd();
    if (position != blockLength) {
      throw in.corrupt("units add up to " + position + " bytes; the catalog says " + blockLength);
    }
    return new Header(
        dimension,
        regionX,
        regionZ,
        seasons,
        base,
        presence,
        rows,
        rawLengths,
        storedLengths,
        crcs,
        unitOffsets,
        blockLength);
  }

  /**
   * Verifies, inflates, and indexes unit {@code u}, whose stored bytes start at {@code offset} of
   * {@code bytes}.
   */
  static Unit readUnit(Header header, int u, byte[] bytes, int offset, String scope)
      throws ChunkIndexException {
    String unitScope = scope + " unit " + u;
    int stored = header.storedLength(u);
    int perUnit = header.rowsPerUnit() * 32;
    int[] offsets = new int[perUnit];
    java.util.Arrays.fill(offsets, -1);
    int expected = 0;
    for (int i = u * perUnit; i < (u + 1) * perUnit; i++) {
      expected += header.present(i) ? 1 : 0;
    }
    if (stored == 0) {
      if (expected != 0) {
        throw new ChunkIndexException(
            ChunkIndexIssueCodes.BLOCK_CORRUPT, "empty unit but present records", unitScope, null);
      }
      return new Unit(header, u, new byte[0], offsets, unitScope);
    }
    if (IndexFormat.crc(bytes, offset, stored) != header.crc(u)) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.BLOCK_CORRUPT, "unit checksum mismatch", unitScope, null);
    }
    byte[] raw =
        IndexFormat.inflate(
            bytes,
            offset,
            stored,
            header.rawLength(u),
            ChunkIndexIssueCodes.BLOCK_CORRUPT,
            unitScope);
    ByteSource in =
        new ByteSource(raw, 0, raw.length, ChunkIndexIssueCodes.BLOCK_CORRUPT, unitScope);
    for (int i = u * perUnit; i < (u + 1) * perUnit; i++) {
      if (header.present(i)) {
        offsets[i - u * perUnit] = in.position();
        RecordCodec.skip(in, header.seasons().length);
      }
    }
    in.requireEnd();
    return new Unit(header, u, raw, offsets, unitScope);
  }
}
