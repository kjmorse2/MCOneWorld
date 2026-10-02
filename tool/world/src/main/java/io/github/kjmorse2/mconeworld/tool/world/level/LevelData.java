// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.level;

import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * What the Tool needs to know about a world, from {@code level.dat} and the seed's saved data.
 * Every field may be absent in an old or damaged world; absence is reported, never guessed.
 *
 * @param dataVersion {@code Data.DataVersion}, the world's data version
 * @param versionName {@code Data.Version.Name}, for display (for example "26.2")
 * @param levelName {@code Data.LevelName}, for display
 * @param seed the world seed: {@code data/minecraft/world_gen_settings.dat} {@code data.seed} in
 *     26.1+, else {@code Data.WorldGenSettings.seed}
 * @param spawn the world spawn: {@code Data.spawn} in 26.x, else {@code Data.SpawnX/Y/Z} in the
 *     Overworld
 * @param serverBrands {@code Data.ServerBrands}: every server brand that has opened the world
 */
public record LevelData(
    OptionalInt dataVersion,
    Optional<String> versionName,
    Optional<String> levelName,
    OptionalLong seed,
    Optional<BlockPosition> spawn,
    List<String> serverBrands) {

  public LevelData {
    Objects.requireNonNull(dataVersion, "dataVersion");
    Objects.requireNonNull(versionName, "versionName");
    Objects.requireNonNull(levelName, "levelName");
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(spawn, "spawn");
    serverBrands = List.copyOf(serverBrands);
  }
}
