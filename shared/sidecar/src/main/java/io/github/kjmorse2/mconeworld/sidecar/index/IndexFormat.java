// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.CRC32C;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Constants and shared encodings of {@code mconeworld-chunk-index} 1.0 ({@code
 * docs/formats/chunk-index-v1.md}): file names, the common file header, season dictionaries, and
 * bounded DEFLATE.
 */
final class IndexFormat {
  static final String FORMAT = "mconeworld-chunk-index";
  static final int MAJOR = 1;
  static final int MINOR = 0;

  static final byte[] CATALOG_MAGIC = {'M', 'C', 'O', 'C'};
  static final byte[] LOG_MAGIC = {'M', 'C', 'O', 'L'};
  static final byte[] SEGMENT_MAGIC = {'M', 'C', 'O', 'S'};
  static final byte[] BLOCK_MAGIC = {'M', 'C', 'O', 'B'};

  /** Size of the common file header: magic, major, minor, index id, number, CRC-32C. */
  static final int HEADER_BYTES = 4 + 2 + 2 + 16 + 8 + 4;

  /** Bytes a log frame adds around its payload: length, length CRC, payload CRC. */
  static final int FRAME_OVERHEAD = 12;

  static final SidecarPath CATALOG = SidecarPath.of("catalog");
  static final String SEGMENTS_DIR = "segments";
  static final int MAX_SEASONS = 65_535;
  static final int MAX_DIMENSIONS = 4_096;
  static final int MAX_BLOCK_HEADER_BYTES = 1 << 20;
  static final int MAX_UNIT_RAW_BYTES = 16 << 20;
  static final int MAX_BLOCK_BYTES = 64 << 20;
  static final int MAX_FRAME_BYTES = 64 << 20;

  private IndexFormat() {}

  static SidecarPath logPath(long generation) {
    return SidecarPath.of(String.format(Locale.ROOT, "deltas-%016d.log", generation));
  }

  static SidecarPath catalogTemp(long generation) {
    return SidecarPath.of(String.format(Locale.ROOT, "catalog.%016d.tmp", generation));
  }

  static SidecarPath segmentPath(long number) {
    return SidecarPath.of(String.format(Locale.ROOT, "%s/seg-%016d.dat", SEGMENTS_DIR, number));
  }

  /** The generation in a log file name, or -1 if the name is not a log name. */
  static long logGeneration(String name) {
    return number(name, "deltas-", ".log");
  }

  /** The generation in a temporary catalog name, or -1. */
  static long catalogTempGeneration(String name) {
    return number(name, "catalog.", ".tmp");
  }

  /** The number in a segment file name, or -1. */
  static long segmentNumber(String name) {
    return number(name, "seg-", ".dat");
  }

  private static long number(String name, String prefix, String suffix) {
    if (!name.startsWith(prefix) || !name.endsWith(suffix)) {
      return -1;
    }
    String digits = name.substring(prefix.length(), name.length() - suffix.length());
    if (digits.length() != 16 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
      return -1;
    }
    return Long.parseLong(digits);
  }

  // ---- common header -------------------------------------------------------------------------

  /**
   * A decoded file header.
   *
   * @param minor the file's minor version; above {@link #MINOR} makes the index read-only here
   * @param indexId the index the file belongs to
   * @param number the catalog or log generation, or the segment number
   */
  record FileHeader(int minor, UUID indexId, long number) {
    boolean newerMinor() {
      return minor > MINOR;
    }
  }

  static void writeHeader(ByteSink out, byte[] magic, UUID indexId, long number) {
    int start = out.size();
    out.bytes(magic).u16(MAJOR).u16(MINOR);
    out.i64(indexId.getMostSignificantBits()).i64(indexId.getLeastSignificantBits()).i64(number);
    CRC32C crc = new CRC32C();
    crc.update(out.array(), start, out.size() - start);
    out.i32((int) crc.getValue());
  }

  /**
   * Decodes and checks the header at the start of {@code bytes}: magic, checksum, and major
   * version.
   *
   * @throws ChunkIndexException {@code HEADER_CORRUPT} or {@code UNSUPPORTED_VERSION}
   */
  static FileHeader readHeader(byte[] bytes, int offset, int length, byte[] magic, String scope)
      throws ChunkIndexException {
    if (length < HEADER_BYTES) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.HEADER_CORRUPT, "file is shorter than its header", scope, null);
    }
    ByteSource in =
        new ByteSource(bytes, offset, HEADER_BYTES, ChunkIndexIssueCodes.HEADER_CORRUPT, scope);
    for (byte b : magic) {
      if (in.u8() != (b & 0xFF)) {
        throw in.corrupt(
            "not a "
                + new String(magic, java.nio.charset.StandardCharsets.US_ASCII)
                + " file (bad magic)");
      }
    }
    int major = in.u16();
    int minor = in.u16();
    UUID indexId = new UUID(in.i64(), in.i64());
    long number = in.i64();
    CRC32C crc = new CRC32C();
    crc.update(bytes, offset, HEADER_BYTES - 4);
    if (in.i32() != (int) crc.getValue()) {
      throw in.corrupt("header checksum mismatch");
    }
    if (major != MAJOR) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.UNSUPPORTED_VERSION,
          "format version "
              + major
              + "."
              + minor
              + " is not supported (this reader: "
              + MAJOR
              + "."
              + MINOR
              + ")",
          scope,
          null);
    }
    if (number < 0) {
      throw in.corrupt("negative generation or segment number");
    }
    return new FileHeader(minor, indexId, number);
  }

  /** Fails with {@code INDEX_ID_MISMATCH} unless {@code header} belongs to {@code indexId}. */
  static void requireIndex(FileHeader header, UUID indexId, String scope)
      throws ChunkIndexException {
    if (!header.indexId().equals(indexId)) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.INDEX_ID_MISMATCH,
          "file belongs to index " + header.indexId() + ", not " + indexId,
          scope,
          null);
    }
  }

  // ---- season dictionaries -------------------------------------------------------------------

  static void writeSeasons(ByteSink out, Collection<SeasonId> sortedSeasons) {
    out.varInt(sortedSeasons.size());
    for (SeasonId s : sortedSeasons) {
      out.i64(s.value().getMostSignificantBits()).i64(s.value().getLeastSignificantBits());
    }
  }

  /** Reads a dictionary, which must be strictly increasing (sorted and unique) and non-nil. */
  static SeasonId[] readSeasons(ByteSource in) throws ChunkIndexException {
    int count = in.count(MAX_SEASONS, 16);
    SeasonId[] seasons = new SeasonId[count];
    for (int i = 0; i < count; i++) {
      try {
        seasons[i] = SeasonId.of(new UUID(in.i64(), in.i64()));
      } catch (RuntimeException e) {
        throw in.corrupt("invalid season id in dictionary", e);
      }
      if (i > 0 && seasons[i - 1].compareTo(seasons[i]) >= 0) {
        throw in.corrupt("season dictionary is not sorted and unique");
      }
    }
    return seasons;
  }

  // ---- DEFLATE -------------------------------------------------------------------------------

  static byte[] deflate(byte[] data, int offset, int length) {
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    try {
      deflater.setInput(data, offset, length);
      deflater.finish();
      ByteSink out = new ByteSink(length / 3 + 64);
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

  /** Inflates raw DEFLATE to exactly {@code rawLength} bytes, or fails with {@code code}. */
  static byte[] inflate(
      byte[] data, int offset, int length, int rawLength, IssueCode code, String scope)
      throws ChunkIndexException {
    Inflater inflater = new Inflater(true);
    try {
      inflater.setInput(data, offset, length);
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
        throw new ChunkIndexException(code, "compressed data is longer than recorded", scope, null);
      }
      if (n != rawLength || !inflater.finished() || inflater.getRemaining() != 0) {
        throw new ChunkIndexException(code, "compressed data length mismatch", scope, null);
      }
      return out;
    } catch (DataFormatException e) {
      throw new ChunkIndexException(code, "invalid compressed data", scope, e);
    } finally {
      inflater.end();
    }
  }

  static int crc(byte[] data, int offset, int length) {
    CRC32C crc = new CRC32C();
    crc.update(data, offset, length);
    return (int) crc.getValue();
  }
}
