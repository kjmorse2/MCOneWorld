// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.init;

import io.github.kjmorse2.mconeworld.domain.ArchiveId;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.tool.core.inventory.WorldInventory;
import java.util.Objects;

/**
 * A completed initialization.
 *
 * @param operation the journaled operation
 * @param archive the new archive's id
 * @param inventory the world as imported (from the final validation scan)
 */
public record InitResult(OperationId operation, ArchiveId archive, WorldInventory inventory) {
  public InitResult {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(archive, "archive");
    Objects.requireNonNull(inventory, "inventory");
  }
}
