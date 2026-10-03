// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.init;

import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.tool.core.inventory.WorldInventory;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import java.util.Objects;

/**
 * What initialization will do, before anything is written: the immutable plan and the world's
 * inventory it was made from (chunk counts per dimension, and header findings worth reviewing).
 *
 * @param locations the world and the sidecar to create
 * @param plan the operation plan the user approves
 * @param inventory the scan the plan's preconditions pin
 */
public record InitPreview(ToolLocations locations, OperationPlan plan, WorldInventory inventory) {
  public InitPreview {
    Objects.requireNonNull(locations, "locations");
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(inventory, "inventory");
  }
}
