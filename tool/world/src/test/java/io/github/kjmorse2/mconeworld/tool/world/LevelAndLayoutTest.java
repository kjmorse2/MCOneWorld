// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.tool.world.layout.DimensionDirectory;
import io.github.kjmorse2.mconeworld.tool.world.layout.LayoutDetector;
import io.github.kjmorse2.mconeworld.tool.world.layout.LayoutKind;
import io.github.kjmorse2.mconeworld.tool.world.layout.WorldLayout;
import io.github.kjmorse2.mconeworld.tool.world.level.LevelData;
import io.github.kjmorse2.mconeworld.tool.world.level.LevelDataReader;
import io.github.kjmorse2.mconeworld.tool.world.lock.SessionLockState;
import io.github.kjmorse2.mconeworld.tool.world.testing.SyntheticWorld;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** level.dat in both generations, both layouts, datapack dimensions, and support classification. */
class LevelAndLayoutTest {
  private static final DimensionKey CUSTOM = DimensionKey.parse("example:sky/islands");

  @TempDir Path temp;

  private SyntheticWorld modernWorld() {
    return SyntheticWorld.modern(temp.resolve("world"))
        .levelDat(4903)
        .seed(-6425566648705237911L)
        .dimension(DimensionKey.OVERWORLD)
        .dimension(DimensionKey.THE_NETHER)
        .dimension(DimensionKey.THE_END)
        .sessionLock();
  }

  @Test
  void a26WorldReadsItsFieldsFromTheirNewLocations() throws Exception {
    List<ValidationIssue> issues = new ArrayList<>();
    LevelData level = LevelDataReader.read(modernWorld().dir(), issues);
    assertThat(issues).isEmpty();
    assertThat(level.dataVersion()).hasValue(4903);
    assertThat(level.versionName()).hasValue("26.2");
    assertThat(level.levelName()).hasValue("Synthetic");
    assertThat(level.seed()).hasValue(-6425566648705237911L);
    assertThat(level.spawn()).hasValue(new BlockPosition(DimensionKey.OVERWORLD, 8, 64, -8));
    assertThat(level.serverBrands()).containsExactly("vanilla");
  }

  @Test
  void aLegacyWorldReadsTheOldFields() throws Exception {
    SyntheticWorld world =
        SyntheticWorld.legacy(temp.resolve("old"))
            .levelDat(
                Map.of(
                    "DataVersion",
                    3953,
                    "SpawnX",
                    -5,
                    "SpawnY",
                    70,
                    "SpawnZ",
                    9,
                    "WorldGenSettings",
                    Map.of("seed", 42L)));
    List<ValidationIssue> issues = new ArrayList<>();
    LevelData level = LevelDataReader.read(world.dir(), issues);
    assertThat(issues).isEmpty();
    assertThat(level.seed()).hasValue(42L);
    assertThat(level.spawn()).hasValue(new BlockPosition(DimensionKey.OVERWORLD, -5, 70, 9));
  }

  @Test
  void missingOrInvalidFieldsAreReportedNotGuessed() throws Exception {
    SyntheticWorld world =
        SyntheticWorld.modern(temp.resolve("bare"))
            .levelDat(
                Map.of("spawn", Map.of("pos", new int[] {1, 2, 3}, "dimension", "Not A Key")));
    List<ValidationIssue> issues = new ArrayList<>();
    LevelData level = LevelDataReader.read(world.dir(), issues);
    assertThat(level.dataVersion()).isEmpty();
    assertThat(level.seed()).isEmpty();
    assertThat(level.spawn()).isEmpty();
    assertThat(issues).hasSize(3);
  }

  @Test
  void theModernLayoutListsVanillaDimensionsFirstThenDatapackOnes() throws Exception {
    SyntheticWorld world =
        modernWorld().dimension(CUSTOM).dimension(DimensionKey.parse("aaa:first"));
    WorldLayout layout = LayoutDetector.detect(world.dir());
    assertThat(layout.kind()).isEqualTo(LayoutKind.MODERN);
    assertThat(layout.findings().issues()).isEmpty();
    assertThat(layout.dimensions())
        .extracting(DimensionDirectory::key)
        .containsExactly(
            DimensionKey.OVERWORLD,
            DimensionKey.THE_NETHER,
            DimensionKey.THE_END,
            DimensionKey.parse("aaa:first"),
            CUSTOM);
    assertThat(layout.dimension(DimensionKey.THE_END).orElseThrow().root())
        .isEqualTo(world.dir().resolve("dimensions/minecraft/the_end"));
  }

  @Test
  void theLegacyLayoutUsesTheRootAndDimDirectories() throws Exception {
    SyntheticWorld world =
        SyntheticWorld.legacy(temp.resolve("old"))
            .levelDat(3953)
            .dimension(DimensionKey.OVERWORLD)
            .dimension(DimensionKey.THE_NETHER)
            .dimension(DimensionKey.THE_END)
            .dimension(CUSTOM);
    WorldLayout layout = LayoutDetector.detect(world.dir());
    assertThat(layout.kind()).isEqualTo(LayoutKind.LEGACY);
    assertThat(layout.dimension(DimensionKey.OVERWORLD).orElseThrow().root())
        .isEqualTo(world.dir());
    assertThat(layout.dimension(DimensionKey.THE_NETHER).orElseThrow().root())
        .isEqualTo(world.dir().resolve("DIM-1"));
    assertThat(layout.dimension(CUSTOM)).isPresent();
  }

  @Test
  void ambiguityInvalidNamesSymlinksAndPaperFoldersAreReported() throws Exception {
    SyntheticWorld world = modernWorld();
    Files.createDirectories(world.dir().resolve("region")); // a stale legacy directory
    Files.createDirectories(world.dir().resolve("dimensions/example/Bad Name/region"));
    Files.createSymbolicLink(world.dir().resolve("dimensions/example/linked"), temp);
    Files.createDirectories(temp.resolve("world_nether"));
    WorldLayout layout = LayoutDetector.detect(world.dir());
    assertThat(layout.kind()).isEqualTo(LayoutKind.MIXED);
    assertThat(layout.findings().issues())
        .extracting(ValidationIssue::code)
        .contains(
            WorldIssueCodes.LAYOUT_MIXED,
            WorldIssueCodes.DIMENSION_NAME_INVALID,
            WorldIssueCodes.SYMLINK_REFUSED,
            WorldIssueCodes.LAYOUT_UNVERIFIED);
  }

  @Test
  void onlyThePinnedVersionOnTheModernLayoutIsWritable() throws Exception {
    WorldInspection pinned = WorldInspector.inspect(modernWorld().dir());
    assertThat(pinned.support().writable()).isTrue();
    assertThat(pinned.lock()).isEqualTo(SessionLockState.FREE);
    assertThat(pinned.findings().issues()).isEmpty();

    SyntheticWorld older =
        SyntheticWorld.modern(temp.resolve("older"))
            .levelDat(4786)
            .dimension(DimensionKey.OVERWORLD);
    WorldInspection inspectOnly = WorldInspector.inspect(older.dir());
    assertThat(inspectOnly.support().writable()).isFalse();
    assertThat(inspectOnly.support().reasons())
        .extracting(ValidationIssue::code)
        .containsExactly(WorldIssueCodes.VERSION_NOT_PINNED);

    SyntheticWorld legacy =
        SyntheticWorld.legacy(temp.resolve("legacy"))
            .levelDat(4903)
            .dimension(DimensionKey.OVERWORLD);
    assertThat(WorldInspector.inspect(legacy.dir()).support().writable()).isFalse();
  }

  @Test
  void aDirectoryWithoutLevelDatIsNotAWorld() throws Exception {
    Files.createDirectories(temp.resolve("empty"));
    assertThatThrownBy(() -> WorldInspector.inspect(temp.resolve("empty")))
        .isInstanceOfSatisfying(
            WorldException.class, e -> assertThat(e.code()).isEqualTo(WorldIssueCodes.NOT_A_WORLD));
    SyntheticWorld garbled =
        SyntheticWorld.modern(temp.resolve("garbled")).file("level.dat", new byte[] {1, 2, 3});
    assertThatThrownBy(() -> WorldInspector.inspect(garbled.dir()))
        .isInstanceOfSatisfying(
            WorldException.class,
            e -> assertThat(e.code()).isEqualTo(WorldIssueCodes.NBT_MALFORMED));
  }
}
