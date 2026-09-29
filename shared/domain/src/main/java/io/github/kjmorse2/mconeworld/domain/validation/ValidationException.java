// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.validation;

import java.io.Serial;
import java.util.List;

/** Thrown by {@link ValidationResult#requireValid()}. It carries the full structured result. */
public final class ValidationException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  /** Not serialized: ValidationIssue causes are diagnostic-only and not portable. */
  private final transient ValidationResult result;

  public ValidationException(ValidationResult result) {
    super(summary(result));
    this.result = result;
  }

  /** The structured result. Branch on its issue codes, not on this exception's message. */
  public ValidationResult result() {
    return result;
  }

  private static String summary(ValidationResult result) {
    List<ValidationIssue> errors = result.withSeverity(Severity.ERROR);
    return errors.size()
        + " validation error(s); first: "
        + (errors.isEmpty() ? "none" : errors.get(0));
  }
}
