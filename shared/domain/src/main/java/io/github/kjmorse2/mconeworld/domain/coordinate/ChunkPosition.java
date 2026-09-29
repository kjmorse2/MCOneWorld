// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import static io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits.CHUNK_SIZE_BLOCKS;
import static io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits.REGION_SIZE_CHUNKS;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import java.util.Comparator;
import java.util.Objects;

/**
 * An absolute chunk position (16×16 block column) in a specific dimension.
 *
 * <p>Every {@code int} pair is a valid chunk. Its blocks are always representable, by the choice of
 * {@link CoordinateLimits}.
 *
 * <p>Natural order is dimension, then X, then Z. It is deterministic and used for reproducible
 * serialization.
 *
 * @param dimension the dimension; required
 * @param x chunk X, equal to {@code floorDiv(blockX, 16)}
 * @param z chunk Z, equal to {@code floorDiv(blockZ, 16)}
 */
public record ChunkPosition(DimensionKey dimension, int x, int z)
    implements Comparable<ChunkPosition> {

  private static final Comparator<ChunkPosition> ORDER =
      Comparator.comparing(ChunkPosition::dimension)
          .thenComparingInt(ChunkPosition::x)
          .thenComparingInt(ChunkPosition::z);

  /** Rejects a missing dimension; every {@code int} pair is otherwise valid. */
  public ChunkPosition {
    Objects.requireNonNull(dimension, "dimension");
  }

  /**
   * Returns the chunk containing {@code block}, using floor division. For example, block {@code -1}
   * is in chunk {@code -1}, and blocks {@code -16..-1} are all in chunk {@code -1}.
   */
  public static ChunkPosition containing(BlockPosition block) {
    // Cannot overflow: CoordinateLimits maps the block range exactly onto the int range.
    return new ChunkPosition(
        block.dimension(),
        Math.toIntExact(Math.floorDiv(block.x(), CHUNK_SIZE_BLOCKS)),
        Math.toIntExact(Math.floorDiv(block.z(), CHUNK_SIZE_BLOCKS)));
  }

  /** Smallest block X in this chunk: {@code x * 16}. */
  public long minBlockX() {
    return (long) x * CHUNK_SIZE_BLOCKS;
  }

  /** Smallest block Z in this chunk: {@code z * 16}. */
  public long minBlockZ() {
    return (long) z * CHUNK_SIZE_BLOCKS;
  }

  /** Largest block X in this chunk: {@code x * 16 + 15}. */
  public long maxBlockX() {
    return minBlockX() + (CHUNK_SIZE_BLOCKS - 1);
  }

  /** Largest block Z in this chunk: {@code z * 16 + 15}. */
  public long maxBlockZ() {
    return minBlockZ() + (CHUNK_SIZE_BLOCKS - 1);
  }

  /** Returns the region containing this chunk, using floor division. Never fails. */
  public RegionPosition region() {
    return RegionPosition.containing(this);
  }

  /** X offset within its region, in {@code 0..31}: {@code floorMod(x, 32)}. */
  public int regionLocalX() {
    return Math.floorMod(x, REGION_SIZE_CHUNKS);
  }

  /** Z offset within its region, in {@code 0..31}: {@code floorMod(z, 32)}. */
  public int regionLocalZ() {
    return Math.floorMod(z, REGION_SIZE_CHUNKS);
  }

  @Override
  public int compareTo(ChunkPosition other) {
    return ORDER.compare(this, other);
  }
}
