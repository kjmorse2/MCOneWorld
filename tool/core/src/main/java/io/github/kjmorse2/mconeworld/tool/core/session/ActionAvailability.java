// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.session;

import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import java.util.List;
import java.util.Objects;

/**
 * Whether an action can start now, and if not, every reason.
 *
 * @param action the action
 * @param blockers why it cannot start; empty when it can
 */
public record ActionAvailability(SessionAction action, List<ValidationIssue> blockers) {
  public ActionAvailability {
    Objects.requireNonNull(action, "action");
    blockers = List.copyOf(blockers);
  }

  public boolean available() {
    return blockers.isEmpty();
  }
}
