// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import java.util.Objects;

/**
 * Why an operation failed, was cancelled, or could not be rolled back.
 *
 * @param code a stable, machine-readable code
 * @param message a bounded, single-line explanation without host paths or stack traces
 */
public record FailureReason(IssueCode code, OperationText message) {
  public FailureReason {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
  }
}
