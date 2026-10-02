// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.region;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits;
import io.github.kjmorse2.mconeworld.domain.coordinate.RegionPosition;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.tool.world.WorldIssueCodes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads region-file headers (the Anvil format, unchanged since 1.2): 1,024 four-byte location
 * entries, a 3-byte big-endian sector offset and a 1-byte sector count, followed by 1,024 four-byte
 * big-endian timestamps. Entry {@code i} is the chunk at local {@code (i % 32, i / 32)}.
 */
public final class RegionFiles {
  /** Bytes in one sector. */
  public static final int SECTOR_BYTES = 4096;

  /** Bytes in the header: the location table plus the timestamp table. */
  public static final int HEADER_BYTES = 2 * SECTOR_BYTES;

  /** Chunks per region file. */
  public static final int ENTRIES = 1024;

  private static final Pattern NAME = Pattern.compile("r\\.(-?\\d{1,10})\\.(-?\\d{1,10})\\.mca");

  private RegionFiles() {}

  /**
   * The region a file name denotes, if it is {@code r.<x>.<z>.mca} with representable coordinates.
   */
  public static Optional<RegionPosition> parseName(DimensionKey dimension, String fileName) {
    Matcher m = NAME.matcher(fileName);
    if (!m.matches()) {
      return Optional.empty();
    }
    long x = Long.parseLong(m.group(1));
    long z = Long.parseLong(m.group(2));
    if (x < CoordinateLimits.MIN_REGION_XZ
        || x > CoordinateLimits.MAX_REGION_XZ
        || z < CoordinateLimits.MIN_REGION_XZ
        || z > CoordinateLimits.MAX_REGION_XZ) {
      return Optional.empty();
    }
    RegionPosition region = new RegionPosition(dimension, (int) x, (int) z);
    // Only the canonical spelling (no leading zeros, no "-0") names a region.
    return fileName.equals(fileName(region)) ? Optional.of(region) : Optional.empty();
  }

  /** The canonical file name of {@code region}. */
  public static String fileName(RegionPosition region) {
    return "r." + region.x() + "." + region.z() + ".mca";
  }

  /**
   * Reads the header of {@code file}, which must be a regular file. Entries that cannot be valid
   * are reported and left out; overlapping entries are reported and kept.
   *
   * @param location where issues are reported
   * @throws IOException if the file cannot be read
   */
  public static RegionScan scan(
      RegionPosition region, RegionKind kind, Path file, IssueLocation location)
      throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Not a regular file: " + file.getFileName());
    }
    try (FileChannel channel =
        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      long size = channel.size();
      if (size == 0) {
        return new RegionScan(region, kind, file, 0, List.of(), List.of());
      }
      if (size < HEADER_BYTES) {
        return new RegionScan(
            region,
            kind,
            file,
            size,
            List.of(),
            List.of(
                ValidationIssue.error(
                    WorldIssueCodes.REGION_HEADER_TRUNCATED,
                    location,
                    "The file has "
                        + size
                        + " bytes, fewer than its "
                        + HEADER_BYTES
                        + "-byte header")));
      }
      ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
      while (header.hasRemaining()) {
        if (channel.read(header, header.position()) < 0) {
          throw new IOException("The header ended early: " + file.getFileName());
        }
      }
      return parse(region, kind, file, size, header.flip(), location);
    }
  }

  static RegionScan parse(
      RegionPosition region,
      RegionKind kind,
      Path file,
      long size,
      ByteBuffer header,
      IssueLocation location) {
    long sectorsInFile = (size + SECTOR_BYTES - 1) / SECTOR_BYTES;
    List<ChunkEntry> chunks = new ArrayList<>();
    List<ValidationIssue> issues = new ArrayList<>();
    BitSet used = new BitSet();
    used.set(0, 2); // the header itself
    for (int i = 0; i < ENTRIES; i++) {
      int location4 = header.getInt(i * 4);
      int offset = location4 >>> 8;
      int count = location4 & 0xFF;
      if (offset == 0 && count == 0) {
        continue;
      }
      IssueLocation at = location.index(i);
      if (offset < 2 || count == 0) {
        issues.add(
            ValidationIssue.error(
                WorldIssueCodes.REGION_ENTRY_INVALID,
                at,
                "Entry " + i + " has offset " + offset + " and " + count + " sectors"));
        continue;
      }
      if ((long) offset + count > sectorsInFile) {
        issues.add(
            ValidationIssue.error(
                WorldIssueCodes.REGION_ENTRY_INVALID,
                at,
                "Entry "
                    + i
                    + " ends at sector "
                    + (offset + count)
                    + ", past the file's "
                    + sectorsInFile));
        continue;
      }
      if (used.get(offset, offset + count).cardinality() > 0) {
        issues.add(
            ValidationIssue.error(
                WorldIssueCodes.REGION_ENTRIES_OVERLAP,
                at,
                "Entry " + i + " shares sectors with another entry or the header"));
      }
      used.set(offset, offset + count);
      ChunkPosition chunk =
          new ChunkPosition(
              region.dimension(),
              region.minChunkX() + (i % CoordinateLimits.REGION_SIZE_CHUNKS),
              region.minChunkZ() + (i / CoordinateLimits.REGION_SIZE_CHUNKS));
      long timestamp = Integer.toUnsignedLong(header.getInt(SECTOR_BYTES + i * 4));
      chunks.add(new ChunkEntry(chunk, offset, count, timestamp));
    }
    return new RegionScan(region, kind, file, size, chunks, issues);
  }
}
