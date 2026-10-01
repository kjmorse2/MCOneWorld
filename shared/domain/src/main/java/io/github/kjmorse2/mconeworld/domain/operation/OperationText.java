// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.TextRules;
import java.util.Objects;

/**
 * Bounded human-readable text in plans and journals: step and check descriptions, rollback
 * instructions, and failure messages. It follows {@link TextRules} with a limit of {@value
 * #MAX_CODE_POINTS} code points, so a single line, never a stack trace.
 *
 * <p>Callers must not put absolute host paths or player-sensitive data in it; the type cannot
 * detect that, so the rule is documented in {@code operation-journal-v1.md}.
 *
 * @param value the validated text
 */
public record OperationText(String value) {
  /** Maximum length in Unicode code points. */
  public static final int MAX_CODE_POINTS = 1024;

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#TEXT_INVALID}
   */
  public OperationText {
    Objects.requireNonNull(value, "value");
    String problem = TextRules.findProblem(value, MAX_CODE_POINTS);
    if (problem != null) {
      throw new InvalidDomainValueException(
          DomainErrorCode.TEXT_INVALID,
          "Invalid operation text \"" + TextRules.excerpt(value) + "\": " + problem);
    }
  }

  /** Equivalent to the constructor. */
  public static OperationText of(String value) {
    return new OperationText(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
