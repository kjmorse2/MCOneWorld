// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import java.util.Comparator;

/**
 * The canonical order of chunks in every index scan: <b>region-major</b>. Chunks sort by dimension,
 * then region X, then region Z, then the chunk's index inside its region ({@code localZ * 32 +
 * localX}, the order Minecraft uses inside a region file).
 *
 * <p>Region-major order lets a store partitioned by region stream any scan by reading one region at
 * a time, and lets a sorted store answer a region with one contiguous range. It differs from {@link
 * ChunkPosition}'s natural order (X, then Z), which is used for serialization, not scanning.
 *
 * <p>Within one dimension, the order is the signed order of {@link #key(int, int)}: a 64-bit key
 * that packs the full {@code int} chunk range without loss:
 *
 * <pre>
 *   bits 63..37  regionX           (27 bits, signed: floorDiv(x, 32))
 *   bits 36..10  regionZ + 2^26    (27 bits, biased so it is non-negative)
 *   bits  9..0   localZ * 32 + localX
 * </pre>
 *
 * <p>The key is a pure function of the coordinates, and {@link #x(long)} and {@link #z(long)}
 * invert it exactly. It is an API value for implementations, not a persisted format by itself:
 * whether a format stores it is that format's decision.
 */
public final class ChunkOrder {
  /** Dimension (by {@link DimensionKey}'s order), then {@link #key(ChunkPosition)}. */
  public static final Comparator<ChunkPosition> COMPARATOR =
      Comparator.comparing(ChunkPosition::dimension).thenComparingLong(ChunkOrder::key);

  private static final int LOCAL_BITS = 10;
  private static final int REGION_Z_SHIFT = LOCAL_BITS;
  private static final int REGION_X_SHIFT = LOCAL_BITS + 27;
  private static final long REGION_Z_BIAS = 1L << 26;
  private static final long REGION_Z_MASK = (1L << 27) - 1;

  private ChunkOrder() {}

  /** The order key of chunk ({@code x}, {@code z}) within its dimension. Never fails. */
  public static long key(int x, int z) {
    long regionX = x >> 5; // arithmetic shift == floorDiv(x, 32)
    long regionZ = z >> 5;
    long local = ((long) (z & 31) << 5) | (x & 31);
    return (regionX << REGION_X_SHIFT) | ((regionZ + REGION_Z_BIAS) << REGION_Z_SHIFT) | local;
  }

  /** The order key of {@code chunk} within its dimension. */
  public static long key(ChunkPosition chunk) {
    return key(chunk.x(), chunk.z());
  }

  /** The chunk X encoded in {@code key}. */
  public static int x(long key) {
    int regionX = (int) (key >> REGION_X_SHIFT);
    return (regionX << 5) | (int) (key & 31);
  }

  /** The chunk Z encoded in {@code key}. */
  public static int z(long key) {
    int regionZ = (int) (((key >>> REGION_Z_SHIFT) & REGION_Z_MASK) - REGION_Z_BIAS);
    return (regionZ << 5) | (int) ((key >>> 5) & 31);
  }

  /** The chunk with order key {@code key} in {@code dimension}. */
  public static ChunkPosition position(DimensionKey dimension, long key) {
    return new ChunkPosition(dimension, x(key), z(key));
  }

  /** The key of the first chunk (index 0) of region ({@code regionX}, {@code regionZ}). */
  public static long regionStart(int regionX, int regionZ) {
    return ((long) regionX << REGION_X_SHIFT) | ((regionZ + REGION_Z_BIAS) << REGION_Z_SHIFT);
  }

  /** The key of the last chunk (index 1023) of region ({@code regionX}, {@code regionZ}). */
  public static long regionEnd(int regionX, int regionZ) {
    return regionStart(regionX, regionZ) | 1023;
  }

  /** The region X of {@code key}. */
  public static int regionX(long key) {
    return (int) (key >> REGION_X_SHIFT);
  }

  /** The region Z of {@code key}. */
  public static int regionZ(long key) {
    return (int) (((key >>> REGION_Z_SHIFT) & REGION_Z_MASK) - REGION_Z_BIAS);
  }

  /** The chunk's index inside its region, {@code localZ * 32 + localX}, in {@code 0..1023}. */
  public static int localIndex(long key) {
    return (int) (key & 1023);
  }
}
