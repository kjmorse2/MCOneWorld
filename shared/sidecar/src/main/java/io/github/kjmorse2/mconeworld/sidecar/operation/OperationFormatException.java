// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.io.Serial;
import java.util.List;

/**
 * A plan or journal entry could not be decoded. Decoding stops at the first problem: these files
 * are machine-written, so one precise finding is enough to classify them. Branch on {@link
 * #code()}, never on the message.
 */
public final class OperationFormatException extends Exception {
  @Serial private static final long serialVersionUID = 1L;

  private final transient ValidationIssue issue;

  public OperationFormatException(IssueCode code, IssueLocation at, String message) {
    super(message + " at " + at);
    this.issue = ValidationIssue.error(code, at, message);
  }

  public IssueCode code() {
    return issue.code();
  }

  /** The finding as a structured issue. */
  public ValidationIssue issue() {
    return issue;
  }

  /** The finding as a one-issue result. */
  public ValidationResult issues() {
    return ValidationResult.of(List.of(issue));
  }
}
