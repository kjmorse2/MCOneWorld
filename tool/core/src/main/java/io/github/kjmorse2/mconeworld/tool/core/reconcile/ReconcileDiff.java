// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.reconcile;

import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What a reconcile changes, for the preview.
 *
 * @param dimensions changes per dimension that has any
 * @param removedByFirstSeason removed records grouped by the season that first observed them (a
 *     season id, or {@code "legacy"})
 * @param protectedRemoved the first {@value #SAMPLE} protected chunks whose records go
 * @param bindsWorld whether the sidecar gets its world binding (it had none)
 */
public record ReconcileDiff(
    List<DimensionChanges> dimensions,
    SortedMap<String, Long> removedByFirstSeason,
    List<ChunkPosition> protectedRemoved,
    boolean bindsWorld) {
  /** How many protected chunks the preview lists. */
  public static final int SAMPLE = 100;

  public ReconcileDiff {
    dimensions = List.copyOf(dimensions);
    removedByFirstSeason = Collections.unmodifiableSortedMap(new TreeMap<>(removedByFirstSeason));
    protectedRemoved = List.copyOf(protectedRemoved);
  }

  public long added() {
    return dimensions.stream().mapToLong(DimensionChanges::added).sum();
  }

  public long removed() {
    return dimensions.stream().mapToLong(DimensionChanges::removed).sum();
  }

  public long protectedRemovedTotal() {
    return dimensions.stream().mapToLong(DimensionChanges::protectedRemoved).sum();
  }

  /** Whether reconciling would change nothing. */
  public boolean nothingToDo() {
    return added() == 0 && removed() == 0 && !bindsWorld;
  }
}
