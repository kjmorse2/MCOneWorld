// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.validation;

import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import java.util.Comparator;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One structured validation finding.
 *
 * <p>Equality and ordering use code, severity, location, message, and remediation. The optional
 * {@linkplain #cause() cause} is diagnostic context only. It is excluded from equality and must
 * never be written to portable formats: stack traces are machine-specific and may contain paths.
 *
 * <p>Natural order is severity (most severe first), then location, code, message, remediation.
 * {@link ValidationResult} uses it for deterministic reports.
 */
public final class ValidationIssue implements Comparable<ValidationIssue> {
  /** Maximum message and remediation length in characters, so reports stay bounded. */
  public static final int MAX_TEXT_LENGTH = 1024;

  private static final Comparator<ValidationIssue> ORDER =
      Comparator.comparing(ValidationIssue::severity)
          .thenComparing(ValidationIssue::location)
          .thenComparing(ValidationIssue::code)
          .thenComparing(ValidationIssue::message)
          .thenComparing(i -> i.remediation == null ? "" : i.remediation);

  private final IssueCode code;
  private final Severity severity;
  private final IssueLocation location;
  private final String message;
  private final @Nullable String remediation;
  private final @Nullable Throwable cause;

  private ValidationIssue(
      IssueCode code,
      Severity severity,
      IssueLocation location,
      String message,
      @Nullable String remediation,
      @Nullable Throwable cause) {
    this.code = Objects.requireNonNull(code, "code");
    this.severity = Objects.requireNonNull(severity, "severity");
    this.location = Objects.requireNonNull(location, "location");
    this.message = bound(Objects.requireNonNull(message, "message"));
    this.remediation = remediation == null ? null : bound(remediation);
    this.cause = cause;
  }

  /** Creates an issue with no remediation hint and no cause. */
  public static ValidationIssue of(
      IssueCode code, Severity severity, IssueLocation location, String message) {
    return new ValidationIssue(code, severity, location, message, null, null);
  }

  /** Shorthand for an {@link Severity#ERROR} issue. */
  public static ValidationIssue error(IssueCode code, IssueLocation location, String message) {
    return of(code, Severity.ERROR, location, message);
  }

  /** Shorthand for a {@link Severity#WARNING} issue. */
  public static ValidationIssue warning(IssueCode code, IssueLocation location, String message) {
    return of(code, Severity.WARNING, location, message);
  }

  /**
   * Reports a rejected domain value as an {@link Severity#ERROR} issue with the same stable code.
   * The exception is attached as the cause.
   */
  public static ValidationIssue fromException(
      IssueLocation location, InvalidDomainValueException e) {
    String message = e.getMessage();
    return new ValidationIssue(
        IssueCode.of(e.code()),
        Severity.ERROR,
        location,
        message == null ? e.code().code() : message,
        null,
        e);
  }

  /** Returns a copy with a remediation hint. */
  public ValidationIssue withRemediation(String remediation) {
    return new ValidationIssue(code, severity, location, message, remediation, cause);
  }

  /** Returns a copy with a diagnostic cause (never serialized; excluded from equality). */
  public ValidationIssue withCause(Throwable cause) {
    return new ValidationIssue(code, severity, location, message, remediation, cause);
  }

  public IssueCode code() {
    return code;
  }

  public Severity severity() {
    return severity;
  }

  public IssueLocation location() {
    return location;
  }

  public String message() {
    return message;
  }

  /** Optional hint telling a person how to fix the problem. */
  public @Nullable String remediation() {
    return remediation;
  }

  /** Optional diagnostic cause. Never part of portable output or equality. */
  public @Nullable Throwable cause() {
    return cause;
  }

  @Override
  public int compareTo(ValidationIssue other) {
    return ORDER.compare(this, other);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof ValidationIssue other
        && code.equals(other.code)
        && severity == other.severity
        && location.equals(other.location)
        && message.equals(other.message)
        && Objects.equals(remediation, other.remediation);
  }

  @Override
  public int hashCode() {
    return Objects.hash(code, severity, location, message, remediation);
  }

  @Override
  public String toString() {
    return severity + " " + code + " at " + location + ": " + message;
  }

  private static String bound(String text) {
    return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH) + "…";
  }
}
