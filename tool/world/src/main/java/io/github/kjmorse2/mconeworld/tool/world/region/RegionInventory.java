// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.region;

import io.github.kjmorse2.mconeworld.domain.coordinate.RegionPosition;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.tool.world.WorldIssueCodes;
import io.github.kjmorse2.mconeworld.tool.world.layout.DimensionDirectory;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Streams the region headers of one dimension, one file at a time in region order, so a world of
 * any size is scanned in bounded memory. Interrupting the calling thread stops the scan between
 * files with an {@link InterruptedIOException}.
 */
public final class RegionInventory {
  private RegionInventory() {}

  /**
   * Scans every {@code r.<x>.<z>.mca} of {@code kind} in {@code dimension}, in ascending region
   * order, handing each header to {@code sink}. Files with other names, and symbolic links, are
   * reported once through {@code skipped} and never read.
   *
   * @param worldDir the world directory, for issue locations
   * @throws InterruptedIOException if the thread is interrupted
   * @throws IOException if a directory or file cannot be read
   */
  public static void scan(
      Path worldDir,
      DimensionDirectory dimension,
      RegionKind kind,
      Consumer<RegionScan> sink,
      Consumer<ValidationIssue> skipped)
      throws IOException {
    Path dir = dimension.directory(kind);
    if (Files.isSymbolicLink(dir)) {
      skipped.accept(
          ValidationIssue.warning(
              WorldIssueCodes.SYMLINK_REFUSED,
              location(worldDir, dir),
              "A symbolic link is not followed"));
      return;
    }
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    TreeMap<RegionPosition, Path> files = new TreeMap<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path file : stream) {
        String name = file.getFileName().toString();
        Optional<RegionPosition> region = RegionFiles.parseName(dimension.key(), name);
        if (region.isEmpty() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
          skipped.accept(
              ValidationIssue.warning(
                  Files.isSymbolicLink(file)
                      ? WorldIssueCodes.SYMLINK_REFUSED
                      : WorldIssueCodes.UNKNOWN_FILE,
                  location(worldDir, file),
                  "Not a region file; ignored"));
          continue;
        }
        files.put(region.get(), file);
      }
    }
    for (var entry : files.entrySet()) {
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("The region scan was interrupted");
      }
      sink.accept(
          RegionFiles.scan(
              entry.getKey(), kind, entry.getValue(), location(worldDir, entry.getValue())));
    }
  }

  /** Every region header of {@code kind} in {@code dimension}; for small worlds and tests. */
  public static List<RegionScan> scanAll(
      Path worldDir, DimensionDirectory dimension, RegionKind kind) throws IOException {
    List<RegionScan> out = new ArrayList<>();
    scan(worldDir, dimension, kind, out::add, issue -> {});
    return out;
  }

  private static IssueLocation location(Path worldDir, Path file) {
    return IssueLocation.root().key(worldDir.relativize(file).toString().replace('\\', '/'));
  }
}
