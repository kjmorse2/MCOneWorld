// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;

/**
 * <b>Representation</b> bounds of the coordinate types: the values they can hold.
 *
 * <p>These are <em>not</em> Minecraft world limits (see {@link WorldBounds}). The block range is
 * chosen as exactly the blocks whose chunk index fits in an {@code int}:
 *
 * <pre>
 *   MIN_BLOCK_XZ = Integer.MIN_VALUE * 16      = -2^35
 *   MAX_BLOCK_XZ = Integer.MAX_VALUE * 16 + 15 =  2^35 - 1
 * </pre>
 *
 * <p>With this range, block-to-chunk and chunk-to-region conversions are total: they cannot fail
 * for any constructed value. All X/Z arithmetic uses {@code long}, so differences between two
 * representable coordinates (at most {@code 2^36}) never overflow.
 *
 * <p>Y is an {@code int}, and every {@code int} is representable. Build-height limits are world
 * bounds.
 */
public final class CoordinateLimits {
  /** Blocks per chunk edge. */
  public static final int CHUNK_SIZE_BLOCKS = 16;

  /** Chunks per region edge (one {@code .mca} file covers 32×32 chunks). */
  public static final int REGION_SIZE_CHUNKS = 32;

  /** Smallest representable absolute or origin block X/Z: {@code -2^35}. */
  public static final long MIN_BLOCK_XZ = (long) Integer.MIN_VALUE * CHUNK_SIZE_BLOCKS;

  /** Largest representable absolute or origin block X/Z: {@code 2^35 - 1}. */
  public static final long MAX_BLOCK_XZ =
      (long) Integer.MAX_VALUE * CHUNK_SIZE_BLOCKS + (CHUNK_SIZE_BLOCKS - 1);

  /** Smallest region X/Z that contains a representable chunk: {@code -2^26}. */
  public static final int MIN_REGION_XZ = Math.floorDiv(Integer.MIN_VALUE, REGION_SIZE_CHUNKS);

  /** Largest region X/Z that contains a representable chunk: {@code 2^26 - 1}. */
  public static final int MAX_REGION_XZ = Math.floorDiv(Integer.MAX_VALUE, REGION_SIZE_CHUNKS);

  private CoordinateLimits() {}

  /** Returns whether {@code value} is a representable block X or Z. */
  public static boolean isRepresentableBlockXZ(long value) {
    return value >= MIN_BLOCK_XZ && value <= MAX_BLOCK_XZ;
  }

  static long requireBlockXZ(String field, long value) {
    if (!isRepresentableBlockXZ(value)) {
      throw new InvalidDomainValueException(
          DomainErrorCode.COORDINATE_OUT_OF_RANGE,
          field
              + " = "
              + value
              + " is outside the representable block range ["
              + MIN_BLOCK_XZ
              + ", "
              + MAX_BLOCK_XZ
              + "]");
    }
    return value;
  }

  static int requireRegionXZ(String field, int value) {
    if (value < MIN_REGION_XZ || value > MAX_REGION_XZ) {
      throw new InvalidDomainValueException(
          DomainErrorCode.COORDINATE_OUT_OF_RANGE,
          field
              + " = "
              + value
              + " is outside the representable region range ["
              + MIN_REGION_XZ
              + ", "
              + MAX_REGION_XZ
              + "]");
    }
    return value;
  }

  static InvalidDomainValueException outOfRange(String description) {
    return new InvalidDomainValueException(DomainErrorCode.COORDINATE_OUT_OF_RANGE, description);
  }
}
