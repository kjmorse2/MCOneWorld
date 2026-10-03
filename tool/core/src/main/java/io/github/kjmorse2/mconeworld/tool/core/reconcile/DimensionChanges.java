// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.reconcile;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import java.util.Objects;

/**
 * How one dimension's records change.
 *
 * @param dimension the dimension
 * @param added chunks the world has and the index lacks, to be recorded
 * @param removed records of chunks the world no longer has, to be reported then removed
 * @param protectedRemoved how many of {@code removed} are protected
 */
public record DimensionChanges(
    DimensionKey dimension, long added, long removed, long protectedRemoved) {
  public DimensionChanges {
    Objects.requireNonNull(dimension, "dimension");
  }
}
