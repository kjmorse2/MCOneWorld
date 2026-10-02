// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

/**
 * Tunables of a chunk-index handle. None of them changes what is stored except {@code rowsPerUnit},
 * which only affects how new blocks are compressed; readers accept any valid value.
 *
 * @param cacheBytes heap budget for cached block headers and decoded units, 1 MiB to 4 GiB
 * @param checkpointMutations logged mutations after which a commit checkpoints, bounding the
 *     in-memory overlay; 1 to 10 000 000
 * @param segmentRollBytes size at which a new segment file is started, 1 MiB to 2 GiB
 * @param rowsPerUnit rows of a region compressed together (1, 2, 4, 8, 16, or 32): smaller units
 *     make random lookups cheaper, larger ones compress better
 * @param maxCatalogBytes refuse catalogs larger than this, up to 2 GiB
 * @param maxLogBytes refuse logs larger than this, up to 2 GiB
 */
public record ChunkIndexOptions(
    long cacheBytes,
    int checkpointMutations,
    long segmentRollBytes,
    int rowsPerUnit,
    long maxCatalogBytes,
    long maxLogBytes) {

  /** Defaults measured in Prompt 08 and 09 (see {@code docs/benchmarks/chunk-index/}). */
  public static final ChunkIndexOptions DEFAULT =
      new ChunkIndexOptions(48L << 20, 100_000, 64L << 20, 4, 512L << 20, 1L << 30);

  /**
   * @throws IllegalArgumentException if a value is outside its documented range
   */
  public ChunkIndexOptions {
    require(cacheBytes >= 1L << 20 && cacheBytes <= 4L << 30, "cacheBytes");
    require(checkpointMutations >= 1 && checkpointMutations <= 10_000_000, "checkpointMutations");
    require(segmentRollBytes >= 1L << 20 && segmentRollBytes <= 2L << 30, "segmentRollBytes");
    require(Integer.bitCount(rowsPerUnit) == 1 && rowsPerUnit <= 32, "rowsPerUnit");
    require(
        maxCatalogBytes >= 1L << 16 && maxCatalogBytes <= Integer.MAX_VALUE - 8, "maxCatalogBytes");
    require(maxLogBytes >= 1L << 16 && maxLogBytes <= Integer.MAX_VALUE - 8, "maxLogBytes");
  }

  /** These options with a different cache budget. */
  public ChunkIndexOptions withCacheBytes(long bytes) {
    return new ChunkIndexOptions(
        bytes, checkpointMutations, segmentRollBytes, rowsPerUnit, maxCatalogBytes, maxLogBytes);
  }

  /** These options with a different checkpoint threshold. */
  public ChunkIndexOptions withCheckpointMutations(int mutations) {
    return new ChunkIndexOptions(
        cacheBytes, mutations, segmentRollBytes, rowsPerUnit, maxCatalogBytes, maxLogBytes);
  }

  /** These options with a different segment roll size. */
  public ChunkIndexOptions withSegmentRollBytes(long bytes) {
    return new ChunkIndexOptions(
        cacheBytes, checkpointMutations, bytes, rowsPerUnit, maxCatalogBytes, maxLogBytes);
  }

  /** These options with a different compression unit. */
  public ChunkIndexOptions withRowsPerUnit(int rows) {
    return new ChunkIndexOptions(
        cacheBytes, checkpointMutations, segmentRollBytes, rows, maxCatalogBytes, maxLogBytes);
  }

  private static void require(boolean condition, String name) {
    if (!condition) {
      throw new IllegalArgumentException(name + " is outside its allowed range");
    }
  }
}
