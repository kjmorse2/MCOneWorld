// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.reconcile;

import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import java.util.Objects;

/**
 * A planned reconcile, before anything is written.
 *
 * @param locations the world and sidecar
 * @param plan the operation plan the user approves
 * @param diff what it changes
 */
public record ReconcilePreview(ToolLocations locations, OperationPlan plan, ReconcileDiff diff) {
  public ReconcilePreview {
    Objects.requireNonNull(locations, "locations");
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(diff, "diff");
  }
}
