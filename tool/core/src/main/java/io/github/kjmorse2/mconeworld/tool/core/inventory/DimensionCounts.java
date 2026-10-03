// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.inventory;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import java.util.Objects;

/**
 * What one dimension holds.
 *
 * @param dimension the dimension
 * @param regionChunks chunks present in its region files (the terrain)
 * @param entityChunks chunks present in its entity files
 * @param poiChunks chunks present in its POI files
 * @param files region, entity, and POI files read (zero-byte files included)
 */
public record DimensionCounts(
    DimensionKey dimension, long regionChunks, long entityChunks, long poiChunks, long files) {
  public DimensionCounts {
    Objects.requireNonNull(dimension, "dimension");
  }
}
