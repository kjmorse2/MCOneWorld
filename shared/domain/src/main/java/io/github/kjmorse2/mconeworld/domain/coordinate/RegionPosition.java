// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import static io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits.REGION_SIZE_CHUNKS;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import java.util.Comparator;
import java.util.Objects;

/**
 * An absolute region position (32×32 chunks, the unit of one Anvil {@code r.X.Z.mca} file) in a
 * specific dimension.
 *
 * <p>Invariant: {@code x} and {@code z} are within {@link CoordinateLimits#MIN_REGION_XZ}..{@link
 * CoordinateLimits#MAX_REGION_XZ}, so every chunk in the region has {@code int} coordinates.
 *
 * <p>Natural order is dimension, then X, then Z.
 *
 * @param dimension the dimension; required
 * @param x region X, equal to {@code floorDiv(chunkX, 32)}
 * @param z region Z, equal to {@code floorDiv(chunkZ, 32)}
 */
public record RegionPosition(DimensionKey dimension, int x, int z)
    implements Comparable<RegionPosition> {

  private static final Comparator<RegionPosition> ORDER =
      Comparator.comparing(RegionPosition::dimension)
          .thenComparingInt(RegionPosition::x)
          .thenComparingInt(RegionPosition::z);

  /**
   * @throws io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException with {@code
   *     COORDINATE_OUT_OF_RANGE} if X or Z is outside the representation bounds
   */
  public RegionPosition {
    Objects.requireNonNull(dimension, "dimension");
    CoordinateLimits.requireRegionXZ("x", x);
    CoordinateLimits.requireRegionXZ("z", z);
  }

  /**
   * Returns the region containing {@code chunk}, using floor division. For example, chunk {@code
   * -1} is in region {@code -1}, and chunks {@code -32..-1} are all in region {@code -1}.
   */
  public static RegionPosition containing(ChunkPosition chunk) {
    return new RegionPosition(
        chunk.dimension(),
        Math.floorDiv(chunk.x(), REGION_SIZE_CHUNKS),
        Math.floorDiv(chunk.z(), REGION_SIZE_CHUNKS));
  }

  /** Smallest chunk X in this region: {@code x * 32}. It cannot overflow, given the invariant. */
  public int minChunkX() {
    return x * REGION_SIZE_CHUNKS;
  }

  /** Smallest chunk Z in this region: {@code z * 32}. */
  public int minChunkZ() {
    return z * REGION_SIZE_CHUNKS;
  }

  /** Largest chunk X in this region: {@code x * 32 + 31}. */
  public int maxChunkX() {
    return minChunkX() + (REGION_SIZE_CHUNKS - 1);
  }

  /** Largest chunk Z in this region: {@code z * 32 + 31}. */
  public int maxChunkZ() {
    return minChunkZ() + (REGION_SIZE_CHUNKS - 1);
  }

  @Override
  public int compareTo(RegionPosition other) {
    return ORDER.compare(this, other);
  }
}
