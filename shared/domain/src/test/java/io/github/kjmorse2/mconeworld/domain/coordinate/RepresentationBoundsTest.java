// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import static io.github.kjmorse2.mconeworld.domain.DimensionKey.OVERWORLD;
import static io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits.MAX_BLOCK_XZ;
import static io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits.MAX_REGION_XZ;
import static io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits.MIN_BLOCK_XZ;
import static io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits.MIN_REGION_XZ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class RepresentationBoundsTest {

  @Test
  void limitsHaveTheDocumentedValues() {
    assertThat(MIN_BLOCK_XZ).isEqualTo(-(1L << 35));
    assertThat(MAX_BLOCK_XZ).isEqualTo((1L << 35) - 1);
    assertThat(MIN_REGION_XZ).isEqualTo(-(1 << 26));
    assertThat(MAX_REGION_XZ).isEqualTo((1 << 26) - 1);
  }

  @Test
  void blockAndOriginAcceptInclusiveLimitsAndRejectOneBeyond() {
    new BlockPosition(OVERWORLD, MIN_BLOCK_XZ, Integer.MIN_VALUE, MAX_BLOCK_XZ);
    new BlockPosition(OVERWORLD, MAX_BLOCK_XZ, Integer.MAX_VALUE, MIN_BLOCK_XZ);
    new DimensionOrigin(OVERWORLD, MIN_BLOCK_XZ, 0, MAX_BLOCK_XZ);

    assertOutOfRange(() -> new BlockPosition(OVERWORLD, MIN_BLOCK_XZ - 1, 0, 0));
    assertOutOfRange(() -> new BlockPosition(OVERWORLD, 0, 0, MAX_BLOCK_XZ + 1));
    assertOutOfRange(() -> new BlockPosition(OVERWORLD, Long.MIN_VALUE, 0, 0));
    assertOutOfRange(() -> new BlockPosition(OVERWORLD, 0, 0, Long.MAX_VALUE));
    assertOutOfRange(() -> new DimensionOrigin(OVERWORLD, MAX_BLOCK_XZ + 1, 0, 0));
  }

  @Test
  void regionAcceptsInclusiveLimitsAndRejectsOneBeyond() {
    RegionPosition max = new RegionPosition(OVERWORLD, MAX_REGION_XZ, MIN_REGION_XZ);
    assertThat(max.maxChunkX()).isEqualTo(Integer.MAX_VALUE);
    assertThat(max.minChunkZ()).isEqualTo(Integer.MIN_VALUE);
    assertOutOfRange(() -> new RegionPosition(OVERWORLD, MAX_REGION_XZ + 1, 0));
    assertOutOfRange(() -> new RegionPosition(OVERWORLD, 0, MIN_REGION_XZ - 1));
  }

  @Test
  void everyChunkIntIsValidAndItsBlocksAreRepresentable() {
    ChunkPosition min = new ChunkPosition(OVERWORLD, Integer.MIN_VALUE, Integer.MIN_VALUE);
    ChunkPosition max = new ChunkPosition(OVERWORLD, Integer.MAX_VALUE, Integer.MAX_VALUE);
    assertThat(min.minBlockX()).isEqualTo(MIN_BLOCK_XZ);
    assertThat(max.maxBlockZ()).isEqualTo(MAX_BLOCK_XZ);
  }

  static void assertOutOfRange(Executable executable) {
    assertThatThrownBy(executable::execute)
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.COORDINATE_OUT_OF_RANGE));
  }
}
