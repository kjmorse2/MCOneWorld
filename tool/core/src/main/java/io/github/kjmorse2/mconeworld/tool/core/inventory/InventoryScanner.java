// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.inventory;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.tool.core.ProgressListener;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspection;
import io.github.kjmorse2.mconeworld.tool.world.layout.DimensionDirectory;
import io.github.kjmorse2.mconeworld.tool.world.region.ChunkEntry;
import io.github.kjmorse2.mconeworld.tool.world.region.RegionInventory;
import io.github.kjmorse2.mconeworld.tool.world.region.RegionKind;
import io.github.kjmorse2.mconeworld.tool.world.region.RegionScan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Scans every region, entity, and POI header of a world, one file at a time, in layout and region
 * order. Memory does not grow with the world. Interrupting the thread stops the scan.
 */
public final class InventoryScanner {
  /** Findings kept in full; further ones are only counted. */
  public static final int MAX_FINDINGS = 1000;

  /** Receives the chunks of each region (terrain) file as it is scanned. */
  @FunctionalInterface
  public interface RegionSink {
    /** Ignores chunks. */
    RegionSink NONE = (dimension, chunks) -> {};

    void accept(DimensionKey dimension, List<ChunkEntry> chunks) throws IOException;
  }

  private InventoryScanner() {}

  /**
   * Scans {@code world}, handing each region file's chunks to {@code sink}.
   *
   * @throws java.io.InterruptedIOException if the thread is interrupted
   * @throws IOException if a directory or file cannot be read, or {@code sink} fails
   */
  public static WorldInventory scan(
      WorldInspection world, RegionSink sink, ProgressListener progress) throws IOException {
    MessageDigest digest = sha256();
    List<DimensionCounts> counts = new ArrayList<>();
    List<ValidationIssue> findings = new ArrayList<>();
    long[] dropped = {0};
    long[] files = {0};
    for (DimensionDirectory dimension : world.layout().dimensions()) {
      EnumMap<RegionKind, Long> chunks = new EnumMap<>(RegionKind.class);
      long dimensionFiles = files[0];
      for (RegionKind kind : RegionKind.values()) {
        try {
          RegionInventory.scan(
              world.worldDir(),
              dimension,
              kind,
              scan -> {
                files[0]++;
                chunks.merge(kind, (long) scan.chunks().size(), Long::sum);
                update(digest, dimension.key(), kind, scan);
                scan.issues().forEach(i -> keep(findings, dropped, i));
                if (kind == RegionKind.REGION && !scan.chunks().isEmpty()) {
                  try {
                    sink.accept(dimension.key(), scan.chunks());
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                }
                progress.progress("Scanning " + dimension.key(), files[0], -1);
              },
              issue -> keep(findings, dropped, issue));
        } catch (UncheckedIOException e) {
          throw e.getCause();
        }
      }
      counts.add(
          new DimensionCounts(
              dimension.key(),
              chunks.getOrDefault(RegionKind.REGION, 0L),
              chunks.getOrDefault(RegionKind.ENTITIES, 0L),
              chunks.getOrDefault(RegionKind.POI, 0L),
              files[0] - dimensionFiles));
    }
    if (dropped[0] > 0) {
      findings.add(
          ValidationIssue.warning(
              findings.getFirst().code(),
              IssueLocation.root(),
              dropped[0] + " further findings are not listed"));
    }
    return new WorldInventory(
        counts,
        Checksum.ofDigest(Checksum.Algorithm.SHA_256, digest.digest()),
        ValidationResult.of(findings));
  }

  private static void keep(List<ValidationIssue> findings, long[] dropped, ValidationIssue issue) {
    if (findings.size() < MAX_FINDINGS) {
      findings.add(issue);
    } else {
      dropped[0]++;
    }
  }

  /** One line per file, then one per valid entry: a canonical, order-fixed text of the headers. */
  private static void update(
      MessageDigest digest, DimensionKey dimension, RegionKind kind, RegionScan scan) {
    StringBuilder text = new StringBuilder(64 + 40 * scan.chunks().size());
    text.append(dimension)
        .append('\t')
        .append(kind.directoryName())
        .append('\t')
        .append(scan.region().x())
        .append('\t')
        .append(scan.region().z())
        .append('\t')
        .append(scan.fileSize())
        .append('\n');
    for (ChunkEntry e : scan.chunks()) {
      text.append(e.chunk().x())
          .append('\t')
          .append(e.chunk().z())
          .append('\t')
          .append(e.sectorOffset())
          .append('\t')
          .append(e.sectorCount())
          .append('\t')
          .append(e.lastSavedEpochSecond())
          .append('\n');
    }
    digest.update(text.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
