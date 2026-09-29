// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import static io.github.kjmorse2.mconeworld.domain.DimensionKey.OVERWORLD;
import static io.github.kjmorse2.mconeworld.domain.DimensionKey.THE_END;
import static io.github.kjmorse2.mconeworld.domain.DimensionKey.THE_NETHER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OriginAlignmentTest {

  @ParameterizedTest(name = "x={0}, z={1}: remainders {2},{3}, aligned={4}")
  @CsvSource({
    "0, 0, 0, 0, true",
    "128, -128, 0, 0, true",
    "1048576, -2097152, 0, 0, true",
    "1, 0, 1, 0, false",
    "127, 0, 127, 0, false",
    "-1, 0, 127, 0, false", // floorMod: negative remainders never occur
    "-127, -129, 1, 127, false",
    "64, 64, 64, 64, false",
    "16, 0, 16, 0, false", // chunk-aligned but not 128-aligned
  })
  void overworldRecommendationIs128(long x, long z, long rx, long rz, boolean aligned) {
    DimensionOrigin origin = new DimensionOrigin(OVERWORLD, x, 64, z);
    OriginAlignment result = OriginAlignment.recommendedFor(origin);

    assertThat(result.alignment()).isEqualTo(128);
    assertThat(result.remainderX()).isEqualTo(rx);
    assertThat(result.remainderZ()).isEqualTo(rz);
    assertThat(result.isAligned()).isEqualTo(aligned);
    assertThat(result.origin()).as("the origin is reported, never rounded").isEqualTo(origin);
  }

  @Test
  void otherDimensionsRecommendChunkAlignment() {
    assertThat(
            OriginAlignment.recommendedFor(new DimensionOrigin(THE_NETHER, 16, 0, -16)).alignment())
        .isEqualTo(16);
    assertThat(OriginAlignment.recommendedFor(new DimensionOrigin(THE_END, 8, 0, 0)).isAligned())
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void nonPositiveAlignmentIsRejected(int alignment) {
    DimensionOrigin origin = new DimensionOrigin(OVERWORLD, 0, 0, 0);
    assertThatThrownBy(() -> OriginAlignment.check(origin, alignment))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.ALIGNMENT_INVALID));
  }
}
