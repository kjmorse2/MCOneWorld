// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world;

import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.tool.world.layout.WorldLayout;
import io.github.kjmorse2.mconeworld.tool.world.level.LevelData;
import io.github.kjmorse2.mconeworld.tool.world.lock.SessionLockState;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A read-only snapshot of a world: its metadata, layout, whether it is open in Minecraft, and
 * whether the Tool may write to it. Region contents are not scanned here (see {@code
 * RegionInventory}); this is cheap enough to refresh on every screen.
 *
 * @param worldDir the world directory
 * @param level what {@code level.dat} and the seed file say
 * @param layout the dimension directories
 * @param lock whether a Minecraft process has the world open, at inspection time
 * @param support whether the Tool may write to the world at all
 * @param findings everything found while reading, layout findings and support reasons included
 */
public record WorldInspection(
    Path worldDir,
    LevelData level,
    WorldLayout layout,
    SessionLockState lock,
    WorldSupport support,
    ValidationResult findings) {
  public WorldInspection {
    Objects.requireNonNull(worldDir, "worldDir");
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(lock, "lock");
    Objects.requireNonNull(support, "support");
    Objects.requireNonNull(findings, "findings");
  }
}
