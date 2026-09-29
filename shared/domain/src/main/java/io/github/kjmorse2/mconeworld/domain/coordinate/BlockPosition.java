// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import java.util.Objects;

/**
 * An absolute block position: Minecraft's real coordinate in a specific dimension.
 *
 * <p>Invariants: {@code x} and {@code z} are within {@link CoordinateLimits#MIN_BLOCK_XZ}..{@link
 * CoordinateLimits#MAX_BLOCK_XZ}. Any {@code int} {@code y} is representable. Game-version world
 * limits are <em>not</em> applied here; use {@link WorldBounds}.
 *
 * @param dimension the dimension this position is in; required
 * @param x absolute block X
 * @param y absolute block Y
 * @param z absolute block Z
 */
public record BlockPosition(DimensionKey dimension, long x, int y, long z) {

  /**
   * @throws io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException with {@code
   *     COORDINATE_OUT_OF_RANGE} if X or Z is outside the representation bounds
   */
  public BlockPosition {
    Objects.requireNonNull(dimension, "dimension");
    CoordinateLimits.requireBlockXZ("x", x);
    CoordinateLimits.requireBlockXZ("z", z);
  }

  /** Returns the chunk containing this block, using floor division. Never fails. */
  public ChunkPosition chunk() {
    return ChunkPosition.containing(this);
  }
}
