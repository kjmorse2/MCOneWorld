// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.level;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.tool.world.WorldIssueCodes;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtException;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtLimits;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtReader;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtCompound;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Reads {@link LevelData} from a world directory, collecting problems as issues. */
public final class LevelDataReader {
  /** {@code level.dat}, relative to the world directory. */
  public static final String LEVEL_DAT = "level.dat";

  /** The seed's saved data in 26.1+, relative to the world directory. */
  public static final String WORLD_GEN_SETTINGS = "data/minecraft/world_gen_settings.dat";

  private static final IssueLocation LEVEL = IssueLocation.root().key(LEVEL_DAT);

  private LevelDataReader() {}

  /**
   * Reads {@code level.dat} (required) and the 26.1+ seed file (optional) from {@code worldDir}.
   * Missing or invalid fields become warnings in {@code issues}; the result holds what was valid.
   *
   * @throws NbtException if {@code level.dat} is not well-formed NBT or exceeds the limits
   * @throws IOException if {@code level.dat} cannot be read
   */
  public static LevelData read(Path worldDir, List<ValidationIssue> issues) throws IOException {
    NbtCompound root = NbtReader.readFile(worldDir.resolve(LEVEL_DAT), NbtLimits.WORLD_DATA);
    Optional<NbtCompound> data = root.compound("Data");
    if (data.isEmpty()) {
      issues.add(invalid(LEVEL, "level.dat has no Data compound"));
      return new LevelData(
          OptionalInt.empty(),
          Optional.empty(),
          Optional.empty(),
          OptionalLong.empty(),
          Optional.empty(),
          List.of());
    }
    NbtCompound d = data.get();
    OptionalInt dataVersion = d.intValue("DataVersion");
    if (dataVersion.isEmpty()) {
      issues.add(invalid(LEVEL.field("DataVersion"), "level.dat has no DataVersion"));
    }
    Optional<String> versionName = d.compound("Version").flatMap(v -> v.string("Name"));
    List<String> brands = new ArrayList<>();
    d.list("ServerBrands")
        .ifPresent(
            list -> {
              for (NbtTag brand : list.values()) {
                if (brand instanceof NbtString s) {
                  brands.add(s.value());
                }
              }
            });
    return new LevelData(
        dataVersion,
        versionName,
        d.string("LevelName"),
        seed(worldDir, d, issues),
        spawn(d, issues),
        brands);
  }

  private static OptionalLong seed(Path worldDir, NbtCompound data, List<ValidationIssue> issues)
      throws IOException {
    Path settings = worldDir.resolve(WORLD_GEN_SETTINGS);
    if (Files.isRegularFile(settings, LinkOption.NOFOLLOW_LINKS)) {
      try {
        OptionalLong seed =
            NbtReader.readFile(settings, NbtLimits.WORLD_DATA)
                .compound("data")
                .map(c -> c.longValue("seed"))
                .orElse(OptionalLong.empty());
        if (seed.isPresent()) {
          return seed;
        }
      } catch (NbtException e) {
        issues.add(
            ValidationIssue.warning(
                    e.code(),
                    IssueLocation.root().key(WORLD_GEN_SETTINGS),
                    String.valueOf(e.getMessage()))
                .withCause(e));
      }
    }
    OptionalLong legacy =
        data.compound("WorldGenSettings")
            .map(c -> c.longValue("seed"))
            .orElse(OptionalLong.empty());
    if (legacy.isPresent()) {
      return legacy;
    }
    OptionalLong oldest = data.longValue("RandomSeed");
    if (oldest.isEmpty()) {
      issues.add(
          invalid(IssueLocation.root().key(WORLD_GEN_SETTINGS), "the world seed was not found"));
    }
    return oldest;
  }

  private static Optional<BlockPosition> spawn(NbtCompound data, List<ValidationIssue> issues) {
    Optional<NbtCompound> spawn = data.compound("spawn");
    try {
      if (spawn.isPresent()) {
        int[] pos = spawn.get().intArray("pos").map(NbtTag.NbtIntArray::values).orElse(new int[0]);
        Optional<String> dimension = spawn.get().string("dimension");
        if (pos.length != 3 || dimension.isEmpty()) {
          issues.add(
              invalid(LEVEL.field("Data").field("spawn"), "the spawn has no pos or dimension"));
          return Optional.empty();
        }
        return Optional.of(
            new BlockPosition(DimensionKey.parse(dimension.get()), pos[0], pos[1], pos[2]));
      }
      OptionalInt x = data.intValue("SpawnX");
      OptionalInt y = data.intValue("SpawnY");
      OptionalInt z = data.intValue("SpawnZ");
      if (x.isPresent() && y.isPresent() && z.isPresent()) {
        return Optional.of(
            new BlockPosition(DimensionKey.OVERWORLD, x.getAsInt(), y.getAsInt(), z.getAsInt()));
      }
    } catch (InvalidDomainValueException e) {
      issues.add(ValidationIssue.fromException(LEVEL.field("Data").field("spawn"), e));
      return Optional.empty();
    }
    issues.add(invalid(LEVEL.field("Data").field("spawn"), "the world spawn was not found"));
    return Optional.empty();
  }

  private static ValidationIssue invalid(IssueLocation at, String message) {
    return ValidationIssue.warning(WorldIssueCodes.LEVEL_DATA_INVALID, at, message);
  }
}
