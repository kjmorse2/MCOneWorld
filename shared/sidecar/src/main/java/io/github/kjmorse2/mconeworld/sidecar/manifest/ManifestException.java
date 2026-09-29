// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import io.github.kjmorse2.mconeworld.domain.validation.Severity;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.io.Serial;
import java.util.EnumSet;
import java.util.Set;

/**
 * A manifest could not be decoded or encoded. It carries every issue found, in deterministic order.
 * Branch on {@link #kinds()} or on issue codes, never on the message.
 */
public final class ManifestException extends Exception {
  @Serial private static final long serialVersionUID = 1L;

  private final transient ValidationResult issues;

  public ManifestException(ValidationResult issues) {
    super(summary(issues));
    if (issues.isValid()) {
      throw new IllegalArgumentException("ManifestException requires at least one error");
    }
    this.issues = issues;
  }

  /** All issues, including any warnings found alongside the errors. */
  public ValidationResult issues() {
    return issues;
  }

  /** The kinds of the {@link Severity#ERROR} issues. */
  public Set<ManifestProblemKind> kinds() {
    Set<ManifestProblemKind> kinds = EnumSet.noneOf(ManifestProblemKind.class);
    for (ValidationIssue issue : issues.withSeverity(Severity.ERROR)) {
      kinds.add(ManifestProblemKind.of(issue.code()));
    }
    return kinds;
  }

  /** The most fundamental error kind present, in {@link ManifestProblemKind} declaration order. */
  public ManifestProblemKind primaryKind() {
    return kinds().iterator().next();
  }

  private static String summary(ValidationResult issues) {
    var errors = issues.withSeverity(Severity.ERROR);
    return errors.size()
        + " manifest error(s)"
        + (errors.isEmpty() ? "" : "; first: " + errors.get(0));
  }
}
