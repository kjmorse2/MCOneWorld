// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import static io.github.kjmorse2.mconeworld.domain.DimensionKey.OVERWORLD;
import static io.github.kjmorse2.mconeworld.domain.DimensionKey.THE_END;
import static io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits.MAX_BLOCK_XZ;
import static io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits.MIN_BLOCK_XZ;
import static io.github.kjmorse2.mconeworld.domain.coordinate.RepresentationBoundsTest.assertOutOfRange;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SeasonLocalTransformTest {

  @ParameterizedTest(name = "origin {0},{1}: absolute {2},{3} -> local {4},{5}")
  @CsvSource({
    "0, 0, 0, 0, 0, 0",
    "1000000, -2000000, 1000000, -2000000, 0, 0",
    "1000000, -2000000, 999999, -2000001, -1, -1",
    "1000000, -2000000, 1000016, -1999984, 16, 16",
    "-128, -128, 0, 0, 128, 128",
    "-128, -128, -129, -129, -1, -1",
  })
  void localIsAbsoluteMinusOriginAndRoundTrips(
      long ox, long oz, long ax, long az, long lx, long lz) {
    DimensionOrigin origin = new DimensionOrigin(OVERWORLD, ox, 70, oz);
    BlockPosition absolute = new BlockPosition(OVERWORLD, ax, -12, az);

    SeasonLocalPosition local = origin.toLocal(absolute);

    assertThat(local.x()).isEqualTo(lx);
    assertThat(local.z()).isEqualTo(lz);
    assertThat(local.y()).as("Y is never transformed").isEqualTo(-12);
    assertThat(local.dimension()).isEqualTo(OVERWORLD);
    assertThat(local.toAbsolute()).isEqualTo(absolute);
  }

  @Test
  void extremeButRepresentableDifferencesDoNotOverflow() {
    DimensionOrigin origin = new DimensionOrigin(OVERWORLD, MAX_BLOCK_XZ, 0, MIN_BLOCK_XZ);
    BlockPosition far = new BlockPosition(OVERWORLD, MIN_BLOCK_XZ, 0, MAX_BLOCK_XZ);
    SeasonLocalPosition local = origin.toLocal(far);
    assertThat(local.x()).isEqualTo(MIN_BLOCK_XZ - MAX_BLOCK_XZ);
    assertThat(local.z()).isEqualTo(MAX_BLOCK_XZ - MIN_BLOCK_XZ);
    assertThat(local.toAbsolute()).isEqualTo(far);
  }

  @Test
  void localPositionThatMapsOutsideRepresentableRangeIsRejected() {
    DimensionOrigin origin = new DimensionOrigin(OVERWORLD, MAX_BLOCK_XZ, 0, 0);
    assertOutOfRange(() -> new SeasonLocalPosition(origin, 1, 0, 0));
    // Near long limits: addExact overflow is reported as out-of-range, not an ArithmeticException.
    assertOutOfRange(() -> new SeasonLocalPosition(origin, Long.MAX_VALUE, 0, 0));
    DimensionOrigin negative = new DimensionOrigin(OVERWORLD, MIN_BLOCK_XZ, 0, MIN_BLOCK_XZ);
    assertOutOfRange(() -> new SeasonLocalPosition(negative, 0, 0, Long.MIN_VALUE));
  }

  @Test
  void originFromAnotherDimensionIsRejected() {
    DimensionOrigin endOrigin = new DimensionOrigin(THE_END, 0, 0, 0);
    assertThatThrownBy(() -> endOrigin.toLocal(new BlockPosition(OVERWORLD, 5, 5, 5)))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.DIMENSION_MISMATCH));
  }

  @Test
  void theEndDoesNotInheritTheOverworldOrigin() {
    DimensionOrigin overworld = new DimensionOrigin(OVERWORLD, 1_000_000, 64, 0);
    DimensionOrigin end = new DimensionOrigin(THE_END, 0, 64, 0);
    BlockPosition endBlock = new BlockPosition(THE_END, 100, 60, 100);
    assertThat(end.toLocal(endBlock).x()).isEqualTo(100);
    assertThatThrownBy(() -> overworld.toLocal(endBlock))
        .isInstanceOf(InvalidDomainValueException.class);
  }
}
