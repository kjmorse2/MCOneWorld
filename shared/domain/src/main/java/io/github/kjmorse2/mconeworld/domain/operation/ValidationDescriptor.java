// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import java.util.Objects;

/**
 * One post-apply validation check. Checks are read-only by definition, so re-running one after an
 * interruption is always safe.
 *
 * @param check what is verified, as a namespaced key the platform understands
 * @param description a human-readable explanation
 */
public record ValidationDescriptor(ActionKey check, OperationText description) {
  public ValidationDescriptor {
    Objects.requireNonNull(check, "check");
    Objects.requireNonNull(description, "description");
  }
}
