// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.io.IOException;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A Tool workflow refused or stopped. {@link #code()} says why, and {@link #findings()} carries
 * every reason with remediation, for the UI and the command line to show.
 */
public final class ToolException extends IOException {
  @Serial private static final long serialVersionUID = 1L;

  private final transient IssueCode code;
  private final transient ValidationResult findings;

  public ToolException(
      IssueCode code, String message, ValidationResult findings, @Nullable Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code, "code");
    this.findings = Objects.requireNonNull(findings, "findings");
  }

  public ToolException(IssueCode code, String message) {
    this(code, message, ValidationResult.ok(), null);
  }

  public IssueCode code() {
    return code;
  }

  public ValidationResult findings() {
    return findings;
  }
}
