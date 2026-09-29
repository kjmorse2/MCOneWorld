// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import static io.github.kjmorse2.mconeworld.domain.DimensionKey.OVERWORLD;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Boundary tables for block -> chunk -> region conversions. The negatives are the point. */
class FloorDivisionTableTest {

  @ParameterizedTest(name = "block {0} -> chunk {1}")
  @CsvSource({
    "0, 0",
    "1, 0",
    "15, 0",
    "16, 1",
    "17, 1",
    "-1, -1",
    "-15, -1",
    "-16, -1",
    "-17, -2",
    "-32, -2",
    "-33, -3",
    "511, 31",
    "512, 32",
    "-512, -32",
    "-513, -33",
    "29999999, 1874999",
    "-30000000, -1875000",
    "34359738367, 2147483647", // MAX_BLOCK_XZ -> Integer.MAX_VALUE
    "-34359738368, -2147483648", // MIN_BLOCK_XZ -> Integer.MIN_VALUE
  })
  void blockToChunk(long block, int expectedChunk) {
    ChunkPosition chunk = new BlockPosition(OVERWORLD, block, 64, block).chunk();
    assertThat(chunk.x()).isEqualTo(expectedChunk);
    assertThat(chunk.z()).isEqualTo(expectedChunk);
    assertThat(chunk.minBlockX()).isLessThanOrEqualTo(block);
    assertThat(chunk.maxBlockX()).isGreaterThanOrEqualTo(block);
    assertThat(chunk.maxBlockX() - chunk.minBlockX()).isEqualTo(15);
  }

  @ParameterizedTest(name = "chunk {0} -> region {1}, local {2}")
  @CsvSource({
    "0, 0, 0",
    "31, 0, 31",
    "32, 1, 0",
    "33, 1, 1",
    "-1, -1, 31",
    "-31, -1, 1",
    "-32, -1, 0",
    "-33, -2, 31",
    "2147483647, 67108863, 31",
    "-2147483648, -67108864, 0",
  })
  void chunkToRegion(int chunkXZ, int expectedRegion, int expectedLocal) {
    ChunkPosition chunk = new ChunkPosition(OVERWORLD, chunkXZ, chunkXZ);
    RegionPosition region = chunk.region();
    assertThat(region.x()).isEqualTo(expectedRegion);
    assertThat(region.z()).isEqualTo(expectedRegion);
    assertThat(chunk.regionLocalX()).isEqualTo(expectedLocal);
    assertThat(chunk.regionLocalZ()).isEqualTo(expectedLocal);
    assertThat(region.minChunkX() + expectedLocal).isEqualTo(chunkXZ);
    assertThat(region.maxChunkX() - region.minChunkX()).isEqualTo(31);
  }

  @ParameterizedTest(name = "block {0} is in region {1}")
  @CsvSource({"511, 0", "512, 1", "-1, -1", "-512, -1", "-513, -2"})
  void blockToRegionComposes(long block, int expectedRegion) {
    assertThat(new BlockPosition(OVERWORLD, block, 0, 0).chunk().region().x())
        .isEqualTo(expectedRegion);
  }
}
