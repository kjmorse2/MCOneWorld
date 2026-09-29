// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;

/**
 * Inclusive <b>world</b> bounds for one dimension of one game version. This is the world-bound
 * check, kept separate from {@link CoordinateLimits} (representation bounds).
 *
 * <p>Nothing in the coordinate types applies these implicitly. Callers validate explicitly with
 * {@link #contains(BlockPosition)}, so tests can model future or custom limits by constructing
 * other bounds.
 *
 * <p>The vanilla values below were verified against the Minecraft 26.2 server:
 *
 * <ul>
 *   <li>Horizontal: {@code Level.MAX_LEVEL_SIZE = 30000000}. {@code
 *       Level#isInWorldBoundsHorizontal} accepts {@code -30000000 <= x < 30000000}, and the same
 *       for Z.
 *   <li>Absolute Y: {@code DimensionType.MIN_Y = -2032} and {@code MAX_Y = 2031}. These derive from
 *       {@code Y_SIZE = (1 << BlockPos.PACKED_Y_LENGTH) - 32} with a 12-bit packed Y.
 *   <li>Per-dimension defaults come from {@code data/minecraft/dimension_type/*.json}:
 *       <ul>
 *         <li>Overworld: {@code min_y -64}, {@code height 384}
 *         <li>Nether: {@code 0}, {@code 256}
 *         <li>End: {@code 0}, {@code 256}
 *       </ul>
 *       Data packs may change these, so platform code should build bounds from the live dimension
 *       type rather than assume these constants.
 * </ul>
 *
 * @param minX smallest valid block X
 * @param maxX largest valid block X
 * @param minY smallest valid block Y
 * @param maxY largest valid block Y
 * @param minZ smallest valid block Z
 * @param maxZ largest valid block Z
 */
public record WorldBounds(long minX, long maxX, int minY, int maxY, long minZ, long maxZ) {

  /** {@code Level.MAX_LEVEL_SIZE} in Minecraft 26.2. Valid X/Z is {@code [-30000000, 29999999]}. */
  public static final int VANILLA_MAX_LEVEL_SIZE = 30_000_000;

  /** Lowest Y any vanilla dimension type may use ({@code DimensionType.MIN_Y}). */
  public static final int VANILLA_ABSOLUTE_MIN_Y = -2032;

  /** Highest Y any vanilla dimension type may use ({@code DimensionType.MAX_Y}). */
  public static final int VANILLA_ABSOLUTE_MAX_Y = 2031;

  /** Vanilla 26.2 default Overworld: horizontal limit, and Y {@code -64..319}. */
  public static final WorldBounds VANILLA_OVERWORLD = vanilla(-64, 384);

  /** Vanilla 26.2 default Nether: horizontal limit, and Y {@code 0..255}. */
  public static final WorldBounds VANILLA_NETHER = vanilla(0, 256);

  /** Vanilla 26.2 default End: horizontal limit, and Y {@code 0..255}. */
  public static final WorldBounds VANILLA_END = vanilla(0, 256);

  /**
   * @throws InvalidDomainValueException with {@code BOUNDS_INVALID} if any minimum exceeds its
   *     maximum, or if X/Z bounds exceed the representation bounds (world bounds can only narrow
   *     what is representable)
   */
  public WorldBounds {
    if (minX > maxX || minY > maxY || minZ > maxZ) {
      throw new InvalidDomainValueException(
          DomainErrorCode.BOUNDS_INVALID,
          "World bounds minimum exceeds maximum: " + describe(minX, maxX, minY, maxY, minZ, maxZ));
    }
    if (!CoordinateLimits.isRepresentableBlockXZ(minX)
        || !CoordinateLimits.isRepresentableBlockXZ(maxX)
        || !CoordinateLimits.isRepresentableBlockXZ(minZ)
        || !CoordinateLimits.isRepresentableBlockXZ(maxZ)) {
      throw new InvalidDomainValueException(
          DomainErrorCode.BOUNDS_INVALID,
          "World bounds exceed the representable block range: "
              + describe(minX, maxX, minY, maxY, minZ, maxZ));
    }
  }

  /**
   * Vanilla horizontal limits with a dimension type's {@code min_y} and {@code height}, matching
   * the dimension-type JSON fields.
   *
   * @throws InvalidDomainValueException with {@code BOUNDS_INVALID} if {@code height <= 0}, or if
   *     the range leaves the vanilla absolute Y range
   */
  public static WorldBounds vanilla(int minY, int height) {
    if (height <= 0) {
      throw new InvalidDomainValueException(
          DomainErrorCode.BOUNDS_INVALID, "height must be positive, got " + height);
    }
    long maxY = (long) minY + height - 1;
    if (minY < VANILLA_ABSOLUTE_MIN_Y || maxY > VANILLA_ABSOLUTE_MAX_Y) {
      throw new InvalidDomainValueException(
          DomainErrorCode.BOUNDS_INVALID,
          "Y range "
              + minY
              + ".."
              + maxY
              + " exceeds vanilla limits "
              + VANILLA_ABSOLUTE_MIN_Y
              + ".."
              + VANILLA_ABSOLUTE_MAX_Y);
    }
    return new WorldBounds(
        -VANILLA_MAX_LEVEL_SIZE,
        VANILLA_MAX_LEVEL_SIZE - 1L,
        minY,
        (int) maxY,
        -VANILLA_MAX_LEVEL_SIZE,
        VANILLA_MAX_LEVEL_SIZE - 1L);
  }

  /** Returns whether the block lies within these bounds on all three axes. */
  public boolean contains(BlockPosition block) {
    return containsHorizontal(block.x(), block.z()) && block.y() >= minY && block.y() <= maxY;
  }

  /** Returns whether X and Z lie within these bounds, ignoring Y. */
  public boolean containsHorizontal(long x, long z) {
    return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
  }

  /** Returns whether every block of {@code chunk} lies within the horizontal bounds. */
  public boolean containsChunkHorizontally(ChunkPosition chunk) {
    return containsHorizontal(chunk.minBlockX(), chunk.minBlockZ())
        && containsHorizontal(chunk.maxBlockX(), chunk.maxBlockZ());
  }

  private static String describe(long minX, long maxX, int minY, int maxY, long minZ, long maxZ) {
    return "x " + minX + ".." + maxX + ", y " + minY + ".." + maxY + ", z " + minZ + ".." + maxZ;
  }
}
