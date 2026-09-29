// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import java.util.Objects;

/**
 * Result of checking a {@link DimensionOrigin} against an alignment in blocks.
 *
 * <p>Alignment is <em>reported</em>, never applied: a misaligned origin is not rounded (root
 * AGENTS.md §8), and the caller decides what to do. Remainders use {@code floorMod}, so they are
 * always in {@code [0, alignment)}, including for negative coordinates. For example, {@code -1} has
 * remainder 127 against 128.
 *
 * <p>Recommendations ({@link #recommendedFor(DimensionOrigin)}):
 *
 * <ul>
 *   <li>Overworld: {@value #RECOMMENDED_OVERWORLD_ALIGNMENT} blocks. This keeps the Overworld
 *       origin chunk-aligned, and its Nether counterpart ({@code / 8}) aligned to a Nether chunk.
 *   <li>Every other dimension: {@value #CHUNK_ALIGNMENT} blocks (a chunk corner), so season-local
 *       chunk boundaries coincide with real ones. No stronger rule is specified for them.
 * </ul>
 *
 * @param origin the origin that was checked
 * @param alignment the alignment in blocks; positive
 */
public record OriginAlignment(DimensionOrigin origin, int alignment) {
  /** Recommended Overworld origin alignment in blocks (root AGENTS.md §8). */
  public static final int RECOMMENDED_OVERWORLD_ALIGNMENT = 128;

  /** Chunk alignment in blocks. */
  public static final int CHUNK_ALIGNMENT = CoordinateLimits.CHUNK_SIZE_BLOCKS;

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#ALIGNMENT_INVALID} if {@code
   *     alignment <= 0}
   */
  public OriginAlignment {
    Objects.requireNonNull(origin, "origin");
    if (alignment <= 0) {
      throw new InvalidDomainValueException(
          DomainErrorCode.ALIGNMENT_INVALID, "alignment must be positive, got " + alignment);
    }
  }

  /** Checks {@code origin} against an explicit alignment. */
  public static OriginAlignment check(DimensionOrigin origin, int alignment) {
    return new OriginAlignment(origin, alignment);
  }

  /** Checks {@code origin} against the recommendation for its dimension (see class docs). */
  public static OriginAlignment recommendedFor(DimensionOrigin origin) {
    int alignment =
        origin.dimension().equals(DimensionKey.OVERWORLD)
            ? RECOMMENDED_OVERWORLD_ALIGNMENT
            : CHUNK_ALIGNMENT;
    return new OriginAlignment(origin, alignment);
  }

  /** {@code floorMod(origin.x, alignment)}, in {@code [0, alignment)}. */
  public long remainderX() {
    return Math.floorMod(origin.x(), (long) alignment);
  }

  /** {@code floorMod(origin.z, alignment)}, in {@code [0, alignment)}. */
  public long remainderZ() {
    return Math.floorMod(origin.z(), (long) alignment);
  }

  /** Returns whether both X and Z are exact multiples of the alignment. */
  public boolean isAligned() {
    return remainderX() == 0 && remainderZ() == 0;
  }
}
