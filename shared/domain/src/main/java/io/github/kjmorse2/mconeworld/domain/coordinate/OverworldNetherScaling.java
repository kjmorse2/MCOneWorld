// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;

/**
 * Explicit, checked scaling between {@code minecraft:overworld} and {@code minecraft:the_nether}.
 *
 * <p>The factor is {@link #NETHER_COORDINATE_SCALE} = 8. It comes from the vanilla {@code
 * minecraft:the_nether} dimension type, which has {@code "coordinate_scale": 8.0} in Minecraft 26.2
 * {@code data/minecraft/dimension_type/the_nether.json}. The Overworld and End types have 1.0.
 *
 * <p>This is deliberately <em>not</em> a generic dimension-scaling facility. Custom dimension types
 * may declare other scales, and data packs can change these, so each method accepts only the two
 * vanilla dimension keys and rejects everything else. If a server changes the Nether scale,
 * platform code must not use this class.
 *
 * <p>Y is copied unchanged. Callers validate the result against the target dimension's {@link
 * WorldBounds}.
 */
public final class OverworldNetherScaling {
  /** Blocks of Overworld X/Z per block of Nether X/Z in vanilla Minecraft. */
  public static final int NETHER_COORDINATE_SCALE = 8;

  private OverworldNetherScaling() {}

  /**
   * Scales an Overworld position to the Nether, requiring X and Z to be exact multiples of 8.
   *
   * @throws InvalidDomainValueException {@code SCALING_UNSUPPORTED_DIMENSION} if not in the
   *     Overworld, or {@code SCALING_NOT_EXACT} if X or Z is not a multiple of 8
   */
  public static BlockPosition toNetherExact(BlockPosition overworld) {
    require(overworld.dimension(), DimensionKey.OVERWORLD);
    return new BlockPosition(
        DimensionKey.THE_NETHER,
        exactDivide("x", overworld.x()),
        overworld.y(),
        exactDivide("z", overworld.z()));
  }

  /**
   * Scales an Overworld position to the Nether with <b>explicit flooring</b>: {@code floorDiv(x,
   * 8)}, so {@code -1 -> -1} and {@code -9 -> -2}. Use this only for presentation or searching,
   * never where an exact correspondence is required.
   *
   * @throws InvalidDomainValueException {@code SCALING_UNSUPPORTED_DIMENSION} if not in the
   *     Overworld
   */
  public static BlockPosition toNetherFloor(BlockPosition overworld) {
    require(overworld.dimension(), DimensionKey.OVERWORLD);
    return new BlockPosition(
        DimensionKey.THE_NETHER,
        Math.floorDiv(overworld.x(), NETHER_COORDINATE_SCALE),
        overworld.y(),
        Math.floorDiv(overworld.z(), NETHER_COORDINATE_SCALE));
  }

  /**
   * Scales a Nether position to the Overworld ({@code x * 8}). Always exact.
   *
   * @throws InvalidDomainValueException {@code SCALING_UNSUPPORTED_DIMENSION} if not in the Nether,
   *     or {@code COORDINATE_OUT_OF_RANGE} if the result is not representable
   */
  public static BlockPosition toOverworld(BlockPosition nether) {
    require(nether.dimension(), DimensionKey.THE_NETHER);
    return new BlockPosition(
        DimensionKey.OVERWORLD, multiply("x", nether.x()), nether.y(), multiply("z", nether.z()));
  }

  /**
   * Returns the Nether origin paired with an Overworld season origin: exactly {@code x / 8}.
   * Misaligned origins are rejected, not rounded (root AGENTS.md §8). An Overworld origin that is a
   * multiple of 128 always yields a chunk-aligned Nether origin.
   *
   * @throws InvalidDomainValueException {@code SCALING_UNSUPPORTED_DIMENSION} or {@code
   *     SCALING_NOT_EXACT}
   */
  public static DimensionOrigin pairedNetherOrigin(DimensionOrigin overworldOrigin) {
    BlockPosition nether = toNetherExact(overworldOrigin.asBlockPosition());
    return new DimensionOrigin(nether.dimension(), nether.x(), nether.y(), nether.z());
  }

  /**
   * Returns whether {@code netherOrigin} is exactly the scaled counterpart of {@code
   * overworldOrigin} on X and Z. Y is ignored, because origin Y is a per-dimension reference
   * height.
   *
   * @throws InvalidDomainValueException {@code SCALING_UNSUPPORTED_DIMENSION} if the dimensions are
   *     not Overworld and Nether respectively
   */
  public static boolean isPaired(DimensionOrigin overworldOrigin, DimensionOrigin netherOrigin) {
    require(overworldOrigin.dimension(), DimensionKey.OVERWORLD);
    require(netherOrigin.dimension(), DimensionKey.THE_NETHER);
    // Compare by multiplying the Nether side up, which cannot overflow for representable values.
    return (long) NETHER_COORDINATE_SCALE * netherOrigin.x() == overworldOrigin.x()
        && (long) NETHER_COORDINATE_SCALE * netherOrigin.z() == overworldOrigin.z();
  }

  private static long exactDivide(String axis, long value) {
    if (Math.floorMod(value, NETHER_COORDINATE_SCALE) != 0) {
      throw new InvalidDomainValueException(
          DomainErrorCode.SCALING_NOT_EXACT,
          "Overworld "
              + axis
              + " = "
              + value
              + " is not a multiple of "
              + NETHER_COORDINATE_SCALE
              + "; exact Nether scaling would require rounding");
    }
    return value / NETHER_COORDINATE_SCALE; // exact, so truncation and floor agree
  }

  private static long multiply(String axis, long netherValue) {
    long result = Math.multiplyExact(netherValue, NETHER_COORDINATE_SCALE);
    if (!CoordinateLimits.isRepresentableBlockXZ(result)) {
      throw CoordinateLimits.outOfRange(
          "Nether "
              + axis
              + " = "
              + netherValue
              + " scales to "
              + result
              + ", outside the representable block range");
    }
    return result;
  }

  private static void require(DimensionKey actual, DimensionKey expected) {
    if (!actual.equals(expected)) {
      throw new InvalidDomainValueException(
          DomainErrorCode.SCALING_UNSUPPORTED_DIMENSION,
          "Overworld/Nether scaling expected " + expected + " but got " + actual);
    }
  }
}
