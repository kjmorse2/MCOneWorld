// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.validation;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A stable, machine-readable validation issue code, for example {@code
 * archive.active_season.not_found}.
 *
 * <p>Grammar: two or more dot-separated segments, each matching {@code [a-z][a-z0-9_]*}, and at
 * most {@value #MAX_LENGTH} characters in total. A published code is never renamed or given a new
 * meaning. The codes of {@link DomainErrorCode} use the same grammar, so a rejected value can be
 * reported as an issue with the identical code.
 *
 * @param value the code text
 */
public record IssueCode(String value) implements Comparable<IssueCode> {
  /** Maximum code length in characters. */
  public static final int MAX_LENGTH = 128;

  private static final Pattern GRAMMAR = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#ISSUE_CODE_MALFORMED}
   */
  public IssueCode {
    Objects.requireNonNull(value, "value");
    if (value.length() > MAX_LENGTH || !GRAMMAR.matcher(value).matches()) {
      throw new InvalidDomainValueException(
          DomainErrorCode.ISSUE_CODE_MALFORMED,
          "Malformed issue code: " + value.length() + " chars");
    }
  }

  /** Equivalent to the constructor. */
  public static IssueCode of(String value) {
    return new IssueCode(value);
  }

  /** The issue code that reports a {@link DomainErrorCode}; the text is identical. */
  public static IssueCode of(DomainErrorCode code) {
    return new IssueCode(code.code());
  }

  @Override
  public int compareTo(IssueCode other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value;
  }
}
