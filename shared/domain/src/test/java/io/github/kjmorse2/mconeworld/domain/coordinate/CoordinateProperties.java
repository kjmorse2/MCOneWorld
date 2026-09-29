// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import static io.github.kjmorse2.mconeworld.domain.DimensionKey.OVERWORLD;
import static io.github.kjmorse2.mconeworld.domain.DimensionKey.THE_NETHER;
import static io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits.MAX_BLOCK_XZ;
import static io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits.MIN_BLOCK_XZ;
import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Property tests over the full representable range. jqwik also injects edge cases (the range
 * limits, 0, ±1) on every run, so the boundaries are always exercised.
 */
class CoordinateProperties {

  @Property
  void chunkAgreesWithMathFloorDivAndFloorMod(
      @ForAll @LongRange(min = MIN_BLOCK_XZ, max = MAX_BLOCK_XZ) long x,
      @ForAll @LongRange(min = MIN_BLOCK_XZ, max = MAX_BLOCK_XZ) long z) {
    ChunkPosition chunk = new BlockPosition(OVERWORLD, x, 0, z).chunk();
    assertThat((long) chunk.x()).isEqualTo(Math.floorDiv(x, 16L));
    assertThat((long) chunk.z()).isEqualTo(Math.floorDiv(z, 16L));
    assertThat(x - chunk.minBlockX()).isEqualTo(Math.floorMod(x, 16L));
    assertThat(z - chunk.minBlockZ()).isEqualTo(Math.floorMod(z, 16L));
  }

  @Property
  void regionAgreesWithMathFloorDivAndFloorMod(@ForAll int chunkX, @ForAll int chunkZ) {
    ChunkPosition chunk = new ChunkPosition(OVERWORLD, chunkX, chunkZ);
    RegionPosition region = chunk.region();
    assertThat(region.x()).isEqualTo(Math.floorDiv(chunkX, 32));
    assertThat(region.z()).isEqualTo(Math.floorDiv(chunkZ, 32));
    assertThat(chunk.regionLocalX()).isEqualTo(Math.floorMod(chunkX, 32)).isBetween(0, 31);
    assertThat(region.minChunkX() + chunk.regionLocalX()).isEqualTo(chunkX);
  }

  @Property
  void absoluteToLocalToAbsoluteIsIdentity(
      @ForAll @LongRange(min = MIN_BLOCK_XZ, max = MAX_BLOCK_XZ) long originX,
      @ForAll @LongRange(min = MIN_BLOCK_XZ, max = MAX_BLOCK_XZ) long originZ,
      @ForAll @LongRange(min = MIN_BLOCK_XZ, max = MAX_BLOCK_XZ) long x,
      @ForAll @LongRange(min = MIN_BLOCK_XZ, max = MAX_BLOCK_XZ) long z,
      @ForAll int y) {
    DimensionOrigin origin = new DimensionOrigin(OVERWORLD, originX, 0, originZ);
    BlockPosition absolute = new BlockPosition(OVERWORLD, x, y, z);
    SeasonLocalPosition local = origin.toLocal(absolute);
    assertThat(local.x()).isEqualTo(x - originX);
    assertThat(local.toAbsolute()).isEqualTo(absolute);
  }

  @Property
  void exactNetherScalingRoundTripsForMultiplesOfEight(
      @ForAll @LongRange(min = MIN_BLOCK_XZ / 8, max = MAX_BLOCK_XZ / 8) long netherX,
      @ForAll @LongRange(min = MIN_BLOCK_XZ / 8, max = MAX_BLOCK_XZ / 8) long netherZ) {
    BlockPosition nether = new BlockPosition(THE_NETHER, netherX, 64, netherZ);
    BlockPosition overworld = OverworldNetherScaling.toOverworld(nether);
    assertThat(OverworldNetherScaling.toNetherExact(overworld)).isEqualTo(nether);
    assertThat(OverworldNetherScaling.toNetherFloor(overworld)).isEqualTo(nether);
  }

  @Property
  void floorNetherScalingBracketsTheOverworldCoordinate(
      @ForAll @LongRange(min = MIN_BLOCK_XZ, max = MAX_BLOCK_XZ) long x) {
    long nether = OverworldNetherScaling.toNetherFloor(new BlockPosition(OVERWORLD, x, 0, 0)).x();
    assertThat(nether * 8).isLessThanOrEqualTo(x);
    assertThat(nether * 8 + 7).isGreaterThanOrEqualTo(x);
  }

  @Property
  void every128AlignedOverworldOriginYieldsChunkAlignedNetherOrigin(
      @ForAll @IntRange(min = -(1 << 28), max = (1 << 28) - 1) int blocksOf128X,
      @ForAll @IntRange(min = -(1 << 28), max = (1 << 28) - 1) int blocksOf128Z) {
    DimensionOrigin overworld =
        new DimensionOrigin(OVERWORLD, 128L * blocksOf128X, 64, 128L * blocksOf128Z);
    assertThat(OriginAlignment.recommendedFor(overworld).isAligned()).isTrue();
    DimensionOrigin nether = OverworldNetherScaling.pairedNetherOrigin(overworld);
    assertThat(OriginAlignment.check(nether, 16).isAligned()).isTrue();
    assertThat(OverworldNetherScaling.isPaired(overworld, nether)).isTrue();
  }
}
