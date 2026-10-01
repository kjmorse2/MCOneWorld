// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import java.util.List;
import java.util.Objects;

/**
 * One ordered step of a plan. It describes a mutation; it never performs one.
 *
 * @param action what the step does, as a namespaced action key the platform understands
 * @param description a human-readable explanation for previews and audit
 * @param retrySafe whether running the step again after an interruption is safe (the step is
 *     idempotent, or detects that it was already applied). Only retry-safe steps may be restarted:
 *     see {@link OperationProgress}.
 * @param targets indexes into the plan's target list, strictly increasing
 */
public record StepDescriptor(
    ActionKey action, OperationText description, boolean retrySafe, List<Integer> targets) {

  /**
   * @throws InvalidDomainValueException with {@code VALUE_INVALID} if the target indexes are
   *     negative or not strictly increasing
   */
  public StepDescriptor {
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(description, "description");
    targets = List.copyOf(targets);
    int previous = -1;
    for (int index : targets) {
      if (index <= previous) {
        throw new InvalidDomainValueException(
            DomainErrorCode.VALUE_INVALID,
            "Step target indexes must be non-negative and strictly increasing");
      }
      previous = index;
    }
  }
}
