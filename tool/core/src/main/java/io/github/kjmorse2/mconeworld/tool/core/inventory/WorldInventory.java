// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.inventory;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of one inventory scan.
 *
 * @param dimensions counts per dimension, in layout order
 * @param digest identifies every region-file header scanned (names, sizes, and every valid entry);
 *     equal digests mean the same headers
 * @param findings damaged headers and skipped files, at most {@value InventoryScanner#MAX_FINDINGS}
 *     (the rest are counted)
 */
public record WorldInventory(
    List<DimensionCounts> dimensions, Checksum digest, ValidationResult findings) {
  public WorldInventory {
    dimensions = List.copyOf(dimensions);
    Objects.requireNonNull(digest, "digest");
    Objects.requireNonNull(findings, "findings");
  }

  public Optional<DimensionCounts> dimension(DimensionKey key) {
    return dimensions.stream().filter(d -> d.dimension().equals(key)).findFirst();
  }

  /** Region chunks in every dimension. */
  public long totalRegionChunks() {
    return dimensions.stream().mapToLong(DimensionCounts::regionChunks).sum();
  }
}
