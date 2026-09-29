// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import static io.github.kjmorse2.mconeworld.domain.DimensionKey.OVERWORLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorldBoundsTest {

  @ParameterizedTest(name = "x={0}, y={1}: inside={2}")
  @CsvSource({
    "0, 64, true",
    "-30000000, -64, true",
    "29999999, 319, true",
    "30000000, 64, false", // Level#isInWorldBoundsHorizontal: x < 30000000
    "-30000001, 64, false",
    "0, -65, false",
    "0, 320, false",
  })
  void vanillaOverworldMatchesVerifiedLimits(long x, int y, boolean inside) {
    assertThat(WorldBounds.VANILLA_OVERWORLD.contains(new BlockPosition(OVERWORLD, x, y, 0)))
        .isEqualTo(inside);
  }

  @Test
  void representableButOutOfWorldPositionsStillConstruct() {
    // World bounds are never applied implicitly (shared/AGENTS.md: no silent clamping).
    BlockPosition far = new BlockPosition(OVERWORLD, 1L << 34, 0, 0);
    assertThat(WorldBounds.VANILLA_OVERWORLD.contains(far)).isFalse();
  }

  @Test
  void vanillaDimensionDefaults() {
    assertThat(WorldBounds.VANILLA_NETHER.minY()).isZero();
    assertThat(WorldBounds.VANILLA_NETHER.maxY()).isEqualTo(255);
    assertThat(WorldBounds.VANILLA_END.maxY()).isEqualTo(255);
    assertThat(WorldBounds.vanilla(-2032, 4064).maxY()).isEqualTo(2031);
  }

  @Test
  void customBoundsModelFutureLimits() {
    WorldBounds small = new WorldBounds(-1000, 999, 0, 127, -1000, 999);
    assertThat(small.containsChunkHorizontally(new ChunkPosition(OVERWORLD, -62, 61)))
        .isTrue(); // -992..-977, 976..991
    // Chunk -63 covers -1008..-993, which crosses -1000.
    assertThat(small.containsChunkHorizontally(new ChunkPosition(OVERWORLD, -63, -63))).isFalse();
  }

  @Test
  void invalidBoundsAreRejectedWithCode() {
    assertInvalid(() -> new WorldBounds(1, 0, 0, 0, 0, 0));
    assertInvalid(() -> new WorldBounds(0, 0, 1, 0, 0, 0));
    assertInvalid(() -> new WorldBounds(Long.MIN_VALUE, 0, 0, 0, 0, 0));
    assertInvalid(() -> WorldBounds.vanilla(0, 0));
    assertInvalid(() -> WorldBounds.vanilla(-2033, 16));
    assertInvalid(() -> WorldBounds.vanilla(2000, 64));
  }

  private static void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
    assertThatThrownBy(executable::execute)
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.BOUNDS_INVALID));
  }
}
