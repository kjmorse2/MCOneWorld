// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import java.util.Objects;

/**
 * A season-local <em>presentation</em> coordinate: an absolute position expressed relative to a
 * {@link DimensionOrigin}. It is derived and never authoritative. Commands, portals, and world data
 * keep using absolute coordinates.
 *
 * <p>The origin is carried with the value, so a local position can never be converted back with the
 * wrong origin, and its dimension is always known.
 *
 * <p>Invariant: {@code origin.x + x} and {@code origin.z + z} are representable absolute block
 * coordinates. {@link #toAbsolute()} therefore never fails for a constructed value.
 *
 * @param origin the origin this position is relative to
 * @param x season-local X, equal to {@code absoluteX - origin.x}
 * @param y absolute Y (Y is not transformed)
 * @param z season-local Z, equal to {@code absoluteZ - origin.z}
 */
public record SeasonLocalPosition(DimensionOrigin origin, long x, int y, long z) {

  /**
   * @throws io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException with {@code
   *     COORDINATE_OUT_OF_RANGE} if the corresponding absolute X or Z is not representable
   */
  public SeasonLocalPosition {
    Objects.requireNonNull(origin, "origin");
    absoluteAxis("x", origin.x(), x);
    absoluteAxis("z", origin.z(), z);
  }

  /** The dimension of the origin, and therefore of this position. */
  public DimensionKey dimension() {
    return origin.dimension();
  }

  /** Converts back to the absolute position. Exact, and never fails by the class invariant. */
  public BlockPosition toAbsolute() {
    return new BlockPosition(
        origin.dimension(), absoluteAxis("x", origin.x(), x), y, absoluteAxis("z", origin.z(), z));
  }

  private static long absoluteAxis(String axis, long originValue, long localValue) {
    long absolute;
    try {
      absolute = Math.addExact(originValue, localValue);
    } catch (ArithmeticException e) {
      throw CoordinateLimits.outOfRange(
          "local " + axis + " = " + localValue + " overflows relative to origin " + originValue);
    }
    if (!CoordinateLimits.isRepresentableBlockXZ(absolute)) {
      throw CoordinateLimits.outOfRange(
          "local "
              + axis
              + " = "
              + localValue
              + " with origin "
              + originValue
              + " gives absolute "
              + absolute
              + ", outside the representable block range");
    }
    return absolute;
  }
}
