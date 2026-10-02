// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.session;

import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The two directories a session works on. They are machine-specific and never stored in the sidecar
 * (root AGENTS.md §9).
 *
 * @param worldDir the Minecraft world directory (the one holding {@code level.dat})
 * @param sidecarDir the MCOneWorld sidecar directory; it may not exist yet
 */
public record ToolLocations(Path worldDir, Path sidecarDir) {
  /** The conventional sidecar name, next to the world directory. */
  public static final String DEFAULT_SIDECAR_NAME = "mconeworld-data";

  public ToolLocations {
    worldDir = Objects.requireNonNull(worldDir, "worldDir").toAbsolutePath().normalize();
    sidecarDir = Objects.requireNonNull(sidecarDir, "sidecarDir").toAbsolutePath().normalize();
  }

  /** The world with the sidecar at its default place: {@code mconeworld-data} beside the world. */
  public static ToolLocations withDefaultSidecar(Path worldDir) {
    Path absolute = worldDir.toAbsolutePath().normalize();
    Path parent = absolute.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("A world directory cannot be a filesystem root");
    }
    return new ToolLocations(absolute, parent.resolve(DEFAULT_SIDECAR_NAME));
  }

  /**
   * An error if the two directories are the same or nested in either direction, after resolving
   * symbolic links of the parts that exist. The sidecar must live outside the save (root §9), and
   * overlapping source and backup locations are refused (root §20).
   */
  public Optional<ValidationIssue> overlap() {
    Path world = real(worldDir);
    Path sidecar = real(sidecarDir);
    if (world.startsWith(sidecar) || sidecar.startsWith(world)) {
      return Optional.of(
          ValidationIssue.error(
                  SessionIssueCodes.PATHS_OVERLAP,
                  IssueLocation.root(),
                  "The sidecar directory and the world directory overlap")
              .withRemediation(
                  "Keep the sidecar beside the world, for example "
                      + DEFAULT_SIDECAR_NAME
                      + " next to the world folder, never inside it."));
    }
    return Optional.empty();
  }

  /** The real path of the longest existing prefix of {@code path}, with the rest appended. */
  private static Path real(Path path) {
    Path existing = path;
    List<Path> rest = new ArrayList<>();
    while (existing != null && !Files.exists(existing)) {
      Path name = existing.getFileName();
      if (name != null) {
        rest.addFirst(name);
      }
      existing = existing.getParent();
    }
    if (existing == null) {
      return path;
    }
    try {
      Path resolved = existing.toRealPath();
      for (Path name : rest) {
        resolved = resolved.resolve(name);
      }
      return resolved;
    } catch (IOException e) {
      return path;
    }
  }
}
