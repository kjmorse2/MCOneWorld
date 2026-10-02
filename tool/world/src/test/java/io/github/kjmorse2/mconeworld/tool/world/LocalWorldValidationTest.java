// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.tool.world.layout.DimensionDirectory;
import io.github.kjmorse2.mconeworld.tool.world.region.RegionInventory;
import io.github.kjmorse2.mconeworld.tool.world.region.RegionKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Reads real worlds kept outside version control (Tool T2 validation, {@code
 * world-format-26.2.md}). Excluded from {@code check}; run by {@code localWorldTest}. Read-only: it
 * never writes into the worlds. Prints the per-dimension counts so they can be compared with the
 * independently measured ones recorded in {@code world-format-26.2.md}.
 */
@Tag("local-worlds")
class LocalWorldValidationTest {

  @Test
  void everyLocalWorldReadsCleanly() throws IOException {
    String root = System.getProperty("mconeworld.localWorlds", "");
    assumeTrue(!root.isEmpty(), "set -PlocalWorlds=<directory of worlds>");
    List<Path> worlds;
    try (Stream<Path> children = Files.list(Path.of(root))) {
      worlds =
          children.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
    }
    assertThat(worlds).isNotEmpty();
    for (Path world : worlds) {
      long modified = lastModified(world);
      WorldInspection inspection = WorldInspector.inspect(world);
      System.out.printf(
          "%n%s: data version %s (%s), layout %s, lock %s, writable %s, brands %s%n",
          world.getFileName(),
          inspection.level().dataVersion(),
          inspection.level().versionName().orElse("?"),
          inspection.layout().kind(),
          inspection.lock(),
          inspection.support().writable(),
          inspection.level().serverBrands());
      inspection.findings().issues().forEach(i -> System.out.println("  finding: " + i));
      for (DimensionDirectory dimension : inspection.layout().dimensions()) {
        for (RegionKind kind : RegionKind.values()) {
          long[] counts = new long[3]; // files, empty files, chunks
          List<ValidationIssue> problems = new ArrayList<>();
          RegionInventory.scan(
              world,
              dimension,
              kind,
              scan -> {
                counts[0]++;
                if (scan.fileSize() == 0) {
                  counts[1]++;
                }
                counts[2] += scan.chunks().size();
                problems.addAll(scan.issues());
              },
              problems::add);
          System.out.printf(
              "  %s %s: %d files (%d empty), %d chunks, %d problems%n",
              dimension.key(),
              kind.directoryName(),
              counts[0],
              counts[1],
              counts[2],
              problems.size());
          problems.stream().limit(5).forEach(p -> System.out.println("    " + p));
        }
      }
      inspection
          .layout()
          .dimension(DimensionKey.THE_END)
          .ifPresent(
              end ->
                  System.out.println(
                      "  dragon fight in End data: "
                          + Files.isRegularFile(
                              end.data().resolve("minecraft/ender_dragon_fight.dat"))));
      assertThat(lastModified(world)).as("the world was not modified").isEqualTo(modified);
    }
  }

  private static long lastModified(Path world) throws IOException {
    try (Stream<Path> files = Files.walk(world)) {
      return files.mapToLong(p -> p.toFile().lastModified()).sum();
    }
  }
}
