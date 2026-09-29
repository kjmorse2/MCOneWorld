// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import java.util.Objects;

/**
 * A non-empty, inclusive, axis-aligned rectangle of chunks in one dimension. This is pure geometry.
 *
 * <p>Whether a rectangle means membership depends on who uses it. A {@code ProtectedRegion}'s
 * rectangle <em>is</em> its protected area. A {@code ChunkEnvelope}'s rectangle is only a derived
 * bounding summary, and {@link #contains(ChunkPosition)} there does not mean a chunk belongs to a
 * season.
 *
 * @param dimension the dimension
 * @param minX smallest chunk X (inclusive)
 * @param minZ smallest chunk Z (inclusive)
 * @param maxX largest chunk X (inclusive)
 * @param maxZ largest chunk Z (inclusive)
 */
public record ChunkRectangle(DimensionKey dimension, int minX, int minZ, int maxX, int maxZ) {

  /**
   * @throws InvalidDomainValueException with {@code BOUNDS_INVALID} if a minimum exceeds its
   *     maximum
   */
  public ChunkRectangle {
    Objects.requireNonNull(dimension, "dimension");
    if (minX > maxX || minZ > maxZ) {
      throw new InvalidDomainValueException(
          DomainErrorCode.BOUNDS_INVALID,
          "Chunk rectangle minimum exceeds maximum: x "
              + minX
              + ".."
              + maxX
              + ", z "
              + minZ
              + ".."
              + maxZ);
    }
  }

  /** The 1×1 rectangle containing exactly {@code chunk}. */
  public static ChunkRectangle of(ChunkPosition chunk) {
    return new ChunkRectangle(chunk.dimension(), chunk.x(), chunk.z(), chunk.x(), chunk.z());
  }

  /**
   * The smallest rectangle containing both corners, which may be given in any order.
   *
   * @throws InvalidDomainValueException with {@code DIMENSION_MISMATCH} if the corners are in
   *     different dimensions
   */
  public static ChunkRectangle spanning(ChunkPosition a, ChunkPosition b) {
    requireSameDimension(a.dimension(), b.dimension());
    return new ChunkRectangle(
        a.dimension(),
        Math.min(a.x(), b.x()),
        Math.min(a.z(), b.z()),
        Math.max(a.x(), b.x()),
        Math.max(a.z(), b.z()));
  }

  /** Geometric containment: same dimension, and X/Z within the inclusive bounds. */
  public boolean contains(ChunkPosition chunk) {
    return dimension.equals(chunk.dimension())
        && chunk.x() >= minX
        && chunk.x() <= maxX
        && chunk.z() >= minZ
        && chunk.z() <= maxZ;
  }

  /** Returns whether the two rectangles share at least one chunk. */
  public boolean intersects(ChunkRectangle other) {
    return dimension.equals(other.dimension)
        && minX <= other.maxX
        && other.minX <= maxX
        && minZ <= other.maxZ
        && other.minZ <= maxZ;
  }

  /** Smallest rectangle containing this one and {@code chunk}. */
  public ChunkRectangle including(ChunkPosition chunk) {
    requireSameDimension(dimension, chunk.dimension());
    return new ChunkRectangle(
        dimension,
        Math.min(minX, chunk.x()),
        Math.min(minZ, chunk.z()),
        Math.max(maxX, chunk.x()),
        Math.max(maxZ, chunk.z()));
  }

  /** Smallest rectangle containing both rectangles. */
  public ChunkRectangle union(ChunkRectangle other) {
    requireSameDimension(dimension, other.dimension);
    return new ChunkRectangle(
        dimension,
        Math.min(minX, other.minX),
        Math.min(minZ, other.minZ),
        Math.max(maxX, other.maxX),
        Math.max(maxZ, other.maxZ));
  }

  /** Width in chunks, between 1 and 2^32. A {@code long}, so the full int range cannot overflow. */
  public long widthChunks() {
    return (long) maxX - minX + 1;
  }

  /** Height (Z extent) in chunks, between 1 and 2^32. */
  public long heightChunks() {
    return (long) maxZ - minZ + 1;
  }

  private static void requireSameDimension(DimensionKey a, DimensionKey b) {
    if (!a.equals(b)) {
      throw new InvalidDomainValueException(
          DomainErrorCode.DIMENSION_MISMATCH, "Chunk rectangle spans " + a + " and " + b);
    }
  }
}
