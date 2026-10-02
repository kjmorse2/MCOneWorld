// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world;

import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.tool.world.layout.LayoutDetector;
import io.github.kjmorse2.mconeworld.tool.world.layout.WorldLayout;
import io.github.kjmorse2.mconeworld.tool.world.level.LevelData;
import io.github.kjmorse2.mconeworld.tool.world.level.LevelDataReader;
import io.github.kjmorse2.mconeworld.tool.world.lock.SessionLockProbe;
import io.github.kjmorse2.mconeworld.tool.world.lock.SessionLockState;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Inspects a world directory, read-only. */
public final class WorldInspector {
  private WorldInspector() {}

  /**
   * Inspects {@code worldDir}.
   *
   * @throws WorldException {@code tool.world.not_a_world} if it is not a world directory, or an NBT
   *     code if {@code level.dat} is unreadable
   * @throws IOException if the directory cannot be read
   */
  public static WorldInspection inspect(Path worldDir) throws IOException {
    if (Files.isSymbolicLink(worldDir) || !Files.isDirectory(worldDir, LinkOption.NOFOLLOW_LINKS)) {
      throw new WorldException(
          WorldIssueCodes.NOT_A_WORLD,
          "Not a directory (symbolic links are not followed): " + worldDir);
    }
    if (!Files.isRegularFile(
        worldDir.resolve(LevelDataReader.LEVEL_DAT), LinkOption.NOFOLLOW_LINKS)) {
      throw new WorldException(
          WorldIssueCodes.NOT_A_WORLD,
          "No level.dat in " + worldDir.getFileName() + ": not a world");
    }
    List<ValidationIssue> findings = new ArrayList<>();
    LevelData level;
    try {
      level = LevelDataReader.read(worldDir, findings);
    } catch (NbtException e) {
      throw new WorldException(e.code(), "level.dat is unreadable: " + e.getMessage(), e);
    }
    WorldLayout layout = LayoutDetector.detect(worldDir);
    findings.addAll(layout.findings().issues());
    SessionLockState lock = SessionLockProbe.probe(worldDir);
    if (lock == SessionLockState.UNKNOWN) {
      findings.add(
          ValidationIssue.warning(
                  WorldIssueCodes.LOCK_UNKNOWN,
                  IssueLocation.root().key(SessionLockProbe.FILE_NAME),
                  "Could not tell whether Minecraft has this world open; it is treated as open")
              .withRemediation(
                  "Check that the server is stopped and that the Tool may write session.lock."));
    }
    WorldSupport support = WorldSupport.classify(level, layout);
    for (ValidationIssue reason : support.reasons()) {
      if (!findings.contains(reason)) {
        findings.add(reason);
      }
    }
    return new WorldInspection(
        worldDir, level, layout, lock, support, ValidationResult.of(findings));
  }
}
