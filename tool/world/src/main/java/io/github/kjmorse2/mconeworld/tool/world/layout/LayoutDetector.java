// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.layout;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.tool.world.WorldIssueCodes;
import io.github.kjmorse2.mconeworld.tool.world.region.RegionKind;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Finds a world's dimension directories. Read-only. */
public final class LayoutDetector {
  /** How deep below {@code dimensions/<namespace>/} a datapack dimension's path may go. */
  static final int MAX_PATH_SEGMENTS = 4;

  private static final List<DimensionKey> VANILLA =
      List.of(DimensionKey.OVERWORLD, DimensionKey.THE_NETHER, DimensionKey.THE_END);

  private LayoutDetector() {}

  /**
   * Detects the layout of {@code worldDir}.
   *
   * @throws IOException if a directory cannot be listed
   */
  public static WorldLayout detect(Path worldDir) throws IOException {
    List<ValidationIssue> findings = new ArrayList<>();
    Path dimensions = worldDir.resolve("dimensions");
    boolean modern =
        isDirectory(worldDir.resolve("dimensions/minecraft/overworld"), findings, worldDir);
    boolean legacy = isDirectory(worldDir.resolve("region"), findings, worldDir);

    TreeMap<DimensionKey, DimensionDirectory> found = new TreeMap<>();
    if (modern) {
      for (DimensionKey key : VANILLA) {
        Path dir = worldDir.resolve("dimensions").resolve(key.namespace()).resolve(key.path());
        if (isDirectory(dir, findings, worldDir)) {
          found.put(key, new DimensionDirectory(key, dir));
        }
      }
    } else if (legacy) {
      found.put(DimensionKey.OVERWORLD, new DimensionDirectory(DimensionKey.OVERWORLD, worldDir));
      for (Map.Entry<String, DimensionKey> e :
          List.of(
              Map.entry("DIM-1", DimensionKey.THE_NETHER),
              Map.entry("DIM1", DimensionKey.THE_END))) {
        Path dir = worldDir.resolve(e.getKey());
        if (isDirectory(dir, findings, worldDir)) {
          found.put(e.getValue(), new DimensionDirectory(e.getValue(), dir));
        }
      }
    }
    // Datapack dimensions live under dimensions/ in both layouts (since 1.16).
    if (isDirectory(dimensions, findings, worldDir)) {
      for (Path namespace : children(dimensions, findings, worldDir)) {
        datapackDimensions(
            namespace, namespace.getFileName().toString(), List.of(), found, findings, worldDir);
      }
    }

    LayoutKind kind;
    if (modern && legacy) {
      kind = LayoutKind.MIXED;
      findings.add(
          ValidationIssue.warning(
                  WorldIssueCodes.LAYOUT_MIXED,
                  IssueLocation.root().key("region"),
                  "The world has both the 26.1+ dimensions/ layout and a legacy region/ directory")
              .withRemediation(
                  "Open the world in Minecraft 26.2 once so it settles on one layout, or remove the"
                      + " stale directory after a backup. The Tool treats the world as inspect-only."));
    } else if (modern) {
      kind = LayoutKind.MODERN;
    } else if (legacy) {
      kind = LayoutKind.LEGACY;
    } else {
      kind = found.isEmpty() ? LayoutKind.NONE : LayoutKind.MODERN;
    }
    if (found.isEmpty()) {
      findings.add(
          ValidationIssue.error(
              WorldIssueCodes.NO_DIMENSIONS,
              IssueLocation.root(),
              "No dimension directory was found"));
    }
    paperFolders(worldDir, findings);

    List<DimensionDirectory> ordered = new ArrayList<>();
    for (DimensionKey key : VANILLA) {
      DimensionDirectory d = found.remove(key);
      if (d != null) {
        ordered.add(d);
      }
    }
    ordered.addAll(found.values());
    return new WorldLayout(worldDir, kind, ordered, ValidationResult.of(findings));
  }

  /** A datapack dimension is a directory below its namespace that holds a {@code region/}. */
  private static void datapackDimensions(
      Path dir,
      String namespace,
      List<String> segments,
      Map<DimensionKey, DimensionDirectory> found,
      List<ValidationIssue> findings,
      Path worldDir)
      throws IOException {
    if (!segments.isEmpty()
        && isDirectory(dir.resolve(RegionKind.REGION.directoryName()), findings, worldDir)) {
      String text = namespace + ":" + String.join("/", segments);
      if (!DimensionKey.isValid(text)) {
        findings.add(
            ValidationIssue.warning(
                WorldIssueCodes.DIMENSION_NAME_INVALID,
                location(worldDir, dir),
                "\"" + text + "\" is not a valid dimension key; the directory is skipped"));
        return;
      }
      DimensionKey key = DimensionKey.parse(text);
      found.putIfAbsent(key, new DimensionDirectory(key, dir));
      return;
    }
    if (segments.size() >= MAX_PATH_SEGMENTS) {
      return;
    }
    for (Path child : children(dir, findings, worldDir)) {
      List<String> next = new ArrayList<>(segments);
      next.add(child.getFileName().toString());
      datapackDimensions(child, namespace, next, found, findings, worldDir);
    }
  }

  /** Paper's legacy per-dimension world folders sit next to the world ({@code <name>_nether}). */
  private static void paperFolders(Path worldDir, List<ValidationIssue> findings) {
    Path parent = worldDir.toAbsolutePath().getParent();
    Path name = worldDir.getFileName();
    if (parent == null || name == null) {
      return;
    }
    for (String suffix : List.of("_nether", "_the_end")) {
      Path sibling = parent.resolve(name + suffix);
      if (Files.isDirectory(sibling, LinkOption.NOFOLLOW_LINKS)) {
        findings.add(
            ValidationIssue.warning(
                    WorldIssueCodes.LAYOUT_UNVERIFIED,
                    IssueLocation.root().key("../" + name + suffix),
                    "A Paper per-dimension world folder sits next to the world; its dimensions are"
                        + " not read, and the world is inspect-only")
                .withRemediation(
                    "Paper 26.2's folder layout is not verified yet (ADR 0010). Report this layout so"
                        + " it can be verified and supported."));
      }
    }
  }

  private static boolean isDirectory(Path dir, List<ValidationIssue> findings, Path worldDir) {
    if (Files.isSymbolicLink(dir)) {
      findings.add(
          ValidationIssue.warning(
              WorldIssueCodes.SYMLINK_REFUSED,
              location(worldDir, dir),
              "A symbolic link is not followed; the directory is skipped"));
      return false;
    }
    return Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS);
  }

  private static List<Path> children(Path dir, List<ValidationIssue> findings, Path worldDir)
      throws IOException {
    List<Path> out = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path child : stream) {
        if (isDirectory(child, findings, worldDir)) {
          out.add(child);
        }
      }
    }
    out.sort(Comparator.comparing(p -> p.getFileName().toString()));
    return out;
  }

  private static IssueLocation location(Path worldDir, Path file) {
    return IssueLocation.root().key(worldDir.relativize(file).toString().replace('\\', '/'));
  }
}
