// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import java.util.Objects;

/**
 * How the plan says a failure will be undone, declared before anything is applied.
 *
 * @param strategy the strategy
 * @param description instructions or explanation for audit and for manual recovery
 */
public record RollbackDeclaration(RollbackStrategy strategy, OperationText description) {
  public RollbackDeclaration {
    Objects.requireNonNull(strategy, "strategy");
    Objects.requireNonNull(description, "description");
  }
}
