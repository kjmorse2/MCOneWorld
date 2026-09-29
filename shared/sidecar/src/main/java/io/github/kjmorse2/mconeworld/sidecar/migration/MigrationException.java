// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.io.Serial;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A migration could not be planned or applied. It carries the stable {@link #code()}, the step
 * reports gathered so far (for example a list of unsupported data), and target-validation issues
 * where relevant.
 */
public final class MigrationException extends Exception {
  @Serial private static final long serialVersionUID = 1L;

  private final transient IssueCode code;
  private final transient List<StepReport> partialReport;
  private final transient @Nullable ValidationResult validation;

  public MigrationException(IssueCode code, String message) {
    this(code, message, List.of(), null);
  }

  public MigrationException(IssueCode code, String message, List<StepReport> partialReport) {
    this(code, message, partialReport, null);
  }

  public MigrationException(
      IssueCode code,
      String message,
      List<StepReport> partialReport,
      @Nullable ValidationResult validation) {
    super(message);
    this.code = code;
    this.partialReport = List.copyOf(partialReport);
    this.validation = validation;
  }

  public IssueCode code() {
    return code;
  }

  /**
   * Reports of the steps that ran before the failure, including the failing step if it recorded
   * anything.
   */
  public List<StepReport> partialReport() {
    return partialReport;
  }

  /** For {@code TARGET_INVALID}: the target reader's findings. */
  public @Nullable ValidationResult validation() {
    return validation;
  }
}
