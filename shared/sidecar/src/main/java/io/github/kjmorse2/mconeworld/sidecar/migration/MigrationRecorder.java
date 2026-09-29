// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Collects what a {@link ManifestMigration} step does, for its {@link StepReport}. */
public final class MigrationRecorder {
  private final MigrationStep step;
  private final Set<StepReport.TransformedField> transformed = new LinkedHashSet<>();
  private final Set<String> preservedExtensions = new LinkedHashSet<>();
  private final List<ValidationIssue> warnings = new ArrayList<>();
  private final Set<StepReport.TransformedField> unsupported = new LinkedHashSet<>();

  MigrationRecorder(MigrationStep step) {
    this.step = step;
  }

  /** Records a transformed field. Recording the same entry twice is harmless. */
  public void transformed(String path, String description) {
    transformed.add(new StepReport.TransformedField(path, description));
  }

  /** Records an extension key carried forward unchanged. */
  public void preservedExtension(String key) {
    preservedExtensions.add(key);
  }

  /** Records a non-lossy warning. */
  public void warning(IssueCode code, IssueLocation location, String message) {
    warnings.add(ValidationIssue.warning(code, location, message));
  }

  /**
   * Records data the step refuses to carry forward. {@link #failIfUnsupported()} then fails the
   * step.
   */
  public void unsupported(String path, String reason) {
    unsupported.add(new StepReport.TransformedField(path, reason));
  }

  /**
   * Fails the step if anything unsupported was recorded. Call it before producing output.
   *
   * @throws MigrationException {@code UNSUPPORTED_DATA}, carrying the partial report
   */
  public void failIfUnsupported() throws MigrationException {
    if (!unsupported.isEmpty()) {
      throw new MigrationException(
          MigrationIssueCodes.UNSUPPORTED_DATA,
          step.id() + " refuses to drop data it cannot carry forward: " + unsupported,
          List.of(report()));
    }
  }

  /** Throws {@code PREREQUISITE_FAILED} with the partial report. */
  public MigrationException prerequisiteFailed(String message) {
    return new MigrationException(
        MigrationIssueCodes.PREREQUISITE_FAILED, step.id() + ": " + message, List.of(report()));
  }

  StepReport report() {
    return new StepReport(
        step,
        List.copyOf(transformed),
        List.copyOf(preservedExtensions),
        ValidationResult.of(warnings).issues(),
        List.copyOf(unsupported));
  }
}
