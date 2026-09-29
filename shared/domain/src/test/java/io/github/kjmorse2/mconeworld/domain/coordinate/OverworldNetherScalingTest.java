// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import static io.github.kjmorse2.mconeworld.domain.DimensionKey.OVERWORLD;
import static io.github.kjmorse2.mconeworld.domain.DimensionKey.THE_END;
import static io.github.kjmorse2.mconeworld.domain.DimensionKey.THE_NETHER;
import static io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits.MAX_BLOCK_XZ;
import static io.github.kjmorse2.mconeworld.domain.coordinate.RepresentationBoundsTest.assertOutOfRange;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OverworldNetherScalingTest {

  @ParameterizedTest(name = "overworld {0} -> nether {1} (exact)")
  @CsvSource({
    "0, 0",
    "8, 1",
    "-8, -1",
    "128, 16",
    "-128, -16",
    "1000000, 125000",
    "-1000000, -125000"
  })
  void exactScalingOfMultiplesOfEight(long overworld, long nether) {
    BlockPosition result =
        OverworldNetherScaling.toNetherExact(
            new BlockPosition(OVERWORLD, overworld, 70, overworld));
    assertThat(result).isEqualTo(new BlockPosition(THE_NETHER, nether, 70, nether));
    assertThat(OverworldNetherScaling.toOverworld(result))
        .isEqualTo(new BlockPosition(OVERWORLD, overworld, 70, overworld));
  }

  @ParameterizedTest
  @ValueSource(longs = {1, 7, 9, -1, -7, -9, 127})
  void exactScalingRejectsNonMultiplesInsteadOfRounding(long overworld) {
    assertThatThrownBy(
            () ->
                OverworldNetherScaling.toNetherExact(new BlockPosition(OVERWORLD, overworld, 0, 0)))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.SCALING_NOT_EXACT));
  }

  @ParameterizedTest(name = "overworld {0} -> nether {1} (floor)")
  @CsvSource({"0, 0", "7, 0", "8, 1", "-1, -1", "-8, -1", "-9, -2", "-16, -2", "-17, -3"})
  void floorScalingUsesFloorDivisionNotTruncation(long overworld, long nether) {
    assertThat(
            OverworldNetherScaling.toNetherFloor(
                new BlockPosition(OVERWORLD, overworld, 0, overworld)))
        .isEqualTo(new BlockPosition(THE_NETHER, nether, 0, nether));
  }

  @Test
  void onlyVanillaOverworldAndNetherAreAccepted() {
    DimensionKey custom = DimensionKey.parse("mymod:nether_like");
    for (DimensionKey wrong : new DimensionKey[] {THE_NETHER, THE_END, custom}) {
      assertUnsupported(
          () -> OverworldNetherScaling.toNetherExact(new BlockPosition(wrong, 8, 0, 8)));
      assertUnsupported(
          () -> OverworldNetherScaling.toNetherFloor(new BlockPosition(wrong, 8, 0, 8)));
    }
    for (DimensionKey wrong : new DimensionKey[] {OVERWORLD, THE_END, custom}) {
      assertUnsupported(
          () -> OverworldNetherScaling.toOverworld(new BlockPosition(wrong, 1, 0, 1)));
    }
  }

  @Test
  void netherToOverworldOverflowIsReported() {
    assertOutOfRange(
        () ->
            OverworldNetherScaling.toOverworld(new BlockPosition(THE_NETHER, MAX_BLOCK_XZ, 0, 0)));
  }

  @Test
  void alignedOverworldOriginPairsWithChunkAlignedNetherOrigin() {
    DimensionOrigin overworld = new DimensionOrigin(OVERWORLD, -1_048_576, 80, 2_097_152);
    DimensionOrigin nether = OverworldNetherScaling.pairedNetherOrigin(overworld);

    assertThat(nether).isEqualTo(new DimensionOrigin(THE_NETHER, -131_072, 80, 262_144));
    assertThat(OverworldNetherScaling.isPaired(overworld, nether)).isTrue();
    assertThat(OriginAlignment.check(nether, OriginAlignment.CHUNK_ALIGNMENT).isAligned()).isTrue();
    assertThat(
            OverworldNetherScaling.isPaired(
                overworld, new DimensionOrigin(THE_NETHER, -131_071, 80, 262_144)))
        .isFalse();
  }

  @Test
  void misalignedOriginIsNotSilentlyPaired() {
    assertThatThrownBy(
            () ->
                OverworldNetherScaling.pairedNetherOrigin(
                    new DimensionOrigin(OVERWORLD, 100, 64, 0)))
        .isInstanceOf(InvalidDomainValueException.class);
  }

  private static void assertUnsupported(org.junit.jupiter.api.function.Executable executable) {
    assertThatThrownBy(executable::execute)
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.SCALING_UNSUPPORTED_DIMENSION));
  }
}
