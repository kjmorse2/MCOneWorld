// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.transition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.archive.ArchiveState;
import io.github.kjmorse2.mconeworld.tool.core.ProgressListener;
import io.github.kjmorse2.mconeworld.tool.core.backup.BackupService;
import io.github.kjmorse2.mconeworld.tool.core.init.SidecarInitializer;
import io.github.kjmorse2.mconeworld.tool.core.inventory.WorldChunks;
import io.github.kjmorse2.mconeworld.tool.core.season.PlannerSettings;
import io.github.kjmorse2.mconeworld.tool.core.season.SeasonPlanner;
import io.github.kjmorse2.mconeworld.tool.core.season.SeasonProposal;
import io.github.kjmorse2.mconeworld.tool.core.session.ActionAvailability;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionAction;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolSession;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs a whole season transition on a temporary <em>copy</em> of each real world kept outside
 * version control: initialize, back up, plan, transition. The originals are only read, and checked
 * unmodified. Excluded from {@code check}; run by {@code localWorldTest}.
 */
@Tag("local-worlds")
class LocalWorldTransitionTest {
  @TempDir Path temp;

  @Test
  void everyLocalWorldCopyTransitions() throws IOException {
    String root = System.getProperty("mconeworld.localWorlds", "");
    assumeTrue(!root.isEmpty(), "set -PlocalWorlds=<directory of worlds>");
    List<Path> worlds;
    try (Stream<Path> children = Files.list(Path.of(root))) {
      worlds =
          children.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
    }
    int n = 0;
    for (Path original : worlds) {
      long before = lastModified(original);
      Path copy = temp.resolve("world-" + n).resolve(original.getFileName().toString());
      copyTree(original, copy);
      ToolLocations l = new ToolLocations(copy, temp.resolve("world-" + n++ + "/mconeworld-data"));
      Clock clock = Clock.systemUTC();
      SidecarInitializer init = new SidecarInitializer(clock, ProgressListener.NONE);
      init.apply(init.preview(l));
      System.out.printf("%n%s (copy):%n", original.getFileName());
      ActionAvailability allowed = ToolSession.inspect(l, clock).action(SessionAction.NEW_SEASON);
      if (!allowed.available()) {
        allowed.blockers().forEach(b -> System.out.println("  not transitioned: " + b));
        assertThat(lastModified(original)).isEqualTo(before);
        continue;
      }
      BackupService backups = new BackupService(clock, ProgressListener.NONE);
      backups.apply(backups.preview(l));
      ArchiveState archive =
          ToolSession.inspect(l, clock).sidecar().manifest().orElseThrow().document().state();
      SeasonProposal proposal =
          new SeasonPlanner(PlannerSettings.DEFAULT)
              .propose(
                  WorldChunks.collect(WorldInspector.inspect(copy), ProgressListener.NONE), archive)
              .orElseThrow();
      SeasonTransition transition = new SeasonTransition(clock, ProgressListener.NONE);
      TransitionPreview preview =
          transition.preview(l, new TransitionRequest(DisplayName.of("Season 2"), proposal, true));
      long start = System.nanoTime();
      transition.apply(preview);
      System.out.printf(
          "  transitioned in %d ms: End %d files, %d bytes, %d records (%d protected) archived;"
              + " origin (%d, %d)%n",
          (System.nanoTime() - start) / 1_000_000,
          preview.endFiles(),
          preview.endBytes(),
          preview.endRecords(),
          preview.protectedEndRecords(),
          proposal.overworld().x(),
          proposal.overworld().z());
      ArchiveState after =
          ToolSession.inspect(l, clock).sidecar().manifest().orElseThrow().document().state();
      assertThat(after.activeSeasonId()).isEqualTo(preview.season());
      assertThat(after.isActivationPending()).isTrue();
      assertThat(SeasonTransition.endPath(l)).doesNotExist();
      assertThat(lastModified(original)).as("the original was not modified").isEqualTo(before);
    }
  }

  private static void copyTree(Path from, Path to) throws IOException {
    try (Stream<Path> walk = Files.walk(from)) {
      for (Path p : walk.toList()) {
        Path target = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
          Files.createDirectories(target);
        } else {
          Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
        }
      }
    }
  }

  private static long lastModified(Path world) throws IOException {
    try (Stream<Path> files = Files.walk(world)) {
      return files.mapToLong(p -> p.toFile().lastModified()).sum();
    }
  }
}
