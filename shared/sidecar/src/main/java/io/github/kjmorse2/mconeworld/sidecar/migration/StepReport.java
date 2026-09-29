// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What one step did. Every list is sorted, so the report is deterministic.
 *
 * @param step the step
 * @param transformed fields that were renamed, converted, restructured, or added
 * @param preservedExtensions extension keys carried forward unchanged
 * @param warnings non-lossy observations
 * @param unsupported data the step refused to carry forward. It is non-empty only in a failure
 *     report.
 */
public record StepReport(
    MigrationStep step,
    List<TransformedField> transformed,
    List<String> preservedExtensions,
    List<ValidationIssue> warnings,
    List<TransformedField> unsupported) {

  public StepReport {
    Objects.requireNonNull(step, "step");
    transformed =
        sorted(
            transformed,
            Comparator.comparing(TransformedField::path)
                .thenComparing(TransformedField::description));
    preservedExtensions = sorted(preservedExtensions, Comparator.naturalOrder());
    warnings = sorted(warnings, Comparator.naturalOrder());
    unsupported =
        sorted(
            unsupported,
            Comparator.comparing(TransformedField::path)
                .thenComparing(TransformedField::description));
  }

  /**
   * One field-level change or refusal.
   *
   * @param path location in the source document, for example {@code $.seasons[*].state}
   * @param description what happened, or why the data is refused
   */
  public record TransformedField(String path, String description) {
    public TransformedField {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(description, "description");
    }
  }

  private static <T> List<T> sorted(List<T> list, Comparator<? super T> order) {
    List<T> copy = new ArrayList<>(list);
    copy.sort(order);
    return List.copyOf(copy);
  }
}
