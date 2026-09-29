// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.validation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * An immutable, deterministically ordered collection of {@link ValidationIssue}s.
 *
 * <p>Issues are sorted by their natural order (severity, location, code, message) at construction,
 * so the same findings always produce the same report regardless of discovery order. Exact
 * duplicates are kept, because they indicate the same problem found twice.
 *
 * @param issues the sorted, unmodifiable issues
 */
public record ValidationResult(List<ValidationIssue> issues) {
  private static final ValidationResult EMPTY = new ValidationResult(List.of());

  /** Copies and sorts {@code issues}. Later changes to the argument have no effect. */
  public ValidationResult {
    List<ValidationIssue> sorted = new ArrayList<>(issues);
    sorted.forEach(i -> Objects.requireNonNull(i, "issue"));
    sorted.sort(null);
    issues = List.copyOf(sorted);
  }

  /** A result with no issues. */
  public static ValidationResult ok() {
    return EMPTY;
  }

  /** Equivalent to the constructor. */
  public static ValidationResult of(Collection<ValidationIssue> issues) {
    return new ValidationResult(List.copyOf(issues));
  }

  /** Returns whether no issue is an {@link Severity#ERROR}. Warnings do not make data invalid. */
  public boolean isValid() {
    return issues.stream().noneMatch(i -> i.severity() == Severity.ERROR);
  }

  /** Returns the issues of exactly {@code severity}, in report order. */
  public List<ValidationIssue> withSeverity(Severity severity) {
    return issues.stream().filter(i -> i.severity() == severity).toList();
  }

  /** Returns whether any issue has {@code code}. */
  public boolean has(IssueCode code) {
    return issues.stream().anyMatch(i -> i.code().equals(code));
  }

  /** Returns a result containing the issues of both. */
  public ValidationResult plus(ValidationResult other) {
    List<ValidationIssue> all = new ArrayList<>(issues);
    all.addAll(other.issues);
    return new ValidationResult(all);
  }

  /**
   * Returns {@code this} if valid.
   *
   * @throws ValidationException carrying this result if any issue is an error
   */
  public ValidationResult requireValid() {
    if (!isValid()) {
      throw new ValidationException(this);
    }
    return this;
  }
}
