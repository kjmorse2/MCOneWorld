// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import java.util.Objects;

/**
 * A season origin in one dimension: the absolute block treated as logical {@code (0, 0)} for
 * season-local presentation. It is never called "the world origin"; Minecraft's {@code (0, 0)}
 * never moves.
 *
 * <p>The transform is X/Z only: {@code localX = absoluteX - x} and {@code localZ = absoluteZ - z}.
 * {@code y} is a reference height recorded with the origin (for example for spawn planning). It
 * does not participate in the transform, and local Y equals absolute Y.
 *
 * <p>Alignment is <em>not</em> enforced, because a misaligned origin must be reportable rather than
 * silently rounded (root AGENTS.md §8). Check it explicitly with {@link OriginAlignment}.
 *
 * @param dimension the dimension this origin applies to. Origins are per-dimension; the End never
 *     inherits the Overworld origin.
 * @param x absolute block X of the origin
 * @param y reference Y (not transformed)
 * @param z absolute block Z of the origin
 */
public record DimensionOrigin(DimensionKey dimension, long x, int y, long z) {

  /**
   * @throws InvalidDomainValueException with {@code COORDINATE_OUT_OF_RANGE} if X or Z is outside
   *     the representation bounds
   */
  public DimensionOrigin {
    Objects.requireNonNull(dimension, "dimension");
    CoordinateLimits.requireBlockXZ("x", x);
    CoordinateLimits.requireBlockXZ("z", z);
  }

  /** Returns the origin itself as an absolute block position. */
  public BlockPosition asBlockPosition() {
    return new BlockPosition(dimension, x, y, z);
  }

  /**
   * Converts an absolute position to season-local coordinates relative to this origin.
   *
   * @throws InvalidDomainValueException with {@link DomainErrorCode#DIMENSION_MISMATCH} if {@code
   *     absolute} is in a different dimension
   */
  public SeasonLocalPosition toLocal(BlockPosition absolute) {
    requireSameDimension(absolute.dimension());
    // Both operands are within ±2^35, so the difference fits in a long; subtractExact is defensive.
    return new SeasonLocalPosition(
        this,
        Math.subtractExact(absolute.x(), x),
        absolute.y(),
        Math.subtractExact(absolute.z(), z));
  }

  void requireSameDimension(DimensionKey other) {
    if (!dimension.equals(other)) {
      throw new InvalidDomainValueException(
          DomainErrorCode.DIMENSION_MISMATCH,
          "Position in " + other + " cannot use an origin in " + dimension);
    }
  }
}
