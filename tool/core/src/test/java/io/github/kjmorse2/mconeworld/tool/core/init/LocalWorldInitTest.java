// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.kjmorse2.mconeworld.tool.core.ProgressListener;
import io.github.kjmorse2.mconeworld.tool.core.inventory.DimensionCounts;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Initializes a sidecar, in a temporary directory, for each real world kept outside version
 * control, and checks that the world was not modified. Excluded from {@code check}; run by {@code
 * localWorldTest}.
 */
@Tag("local-worlds")
class LocalWorldInitTest {
  @TempDir Path temp;

  @Test
  void everyLocalWorldInitializes() throws IOException {
    String root = System.getProperty("mconeworld.localWorlds", "");
    assumeTrue(!root.isEmpty(), "set -PlocalWorlds=<directory of worlds>");
    List<Path> worlds;
    try (Stream<Path> children = Files.list(Path.of(root))) {
      worlds =
          children.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
    }
    int n = 0;
    for (Path world : worlds) {
      long before = lastModified(world);
      ToolLocations locations = new ToolLocations(world, temp.resolve("sidecar-" + n++));
      long start = System.nanoTime();
      SidecarInitializer init = new SidecarInitializer(Clock.systemUTC(), ProgressListener.NONE);
      InitResult result = init.apply(init.preview(locations));
      System.out.printf(
          "%n%s: initialized in %d ms, archive %s%n",
          world.getFileName(), (System.nanoTime() - start) / 1_000_000, result.archive());
      for (DimensionCounts c : result.inventory().dimensions()) {
        System.out.printf("  %s: %d chunks recorded as legacy%n", c.dimension(), c.regionChunks());
      }
      var status = ToolSession.inspect(locations, Clock.systemUTC());
      status.findings().issues().forEach(i -> System.out.println("  finding: " + i));
      assertThat(status.findings().isValid()).isTrue();
      var reconcile =
          new io.github.kjmorse2.mconeworld.tool.core.reconcile.Reconciler(
                  Clock.systemUTC(), ProgressListener.NONE)
              .preview(locations, false);
      System.out.println(
          "  reconcile right after: nothing to do = " + reconcile.diff().nothingToDo());
      assertThat(reconcile.diff().nothingToDo()).isTrue();
      assertThat(lastModified(world)).as("the world was not modified").isEqualTo(before);
    }
  }

  private static long lastModified(Path world) throws IOException {
    try (Stream<Path> files = Files.walk(world)) {
      return files.mapToLong(p -> p.toFile().lastModified()).sum();
    }
  }
}
