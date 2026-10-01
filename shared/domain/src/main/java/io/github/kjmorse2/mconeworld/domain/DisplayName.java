// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * User-editable display text for seasons, portal markers, and protected regions.
 *
 * <p>A display name is <b>not an identifier</b>. It need not be unique, and renaming never changes
 * identity. It is bounded at this trust boundary because it is shown in chat, UIs, and reports.
 *
 * <p>Rules (checked, never silently repaired):
 *
 * <ul>
 *   <li>1 to {@value #MAX_CODE_POINTS} Unicode code points
 *   <li>no leading or trailing whitespace. It is rejected rather than trimmed, so the stored value
 *       is exactly what was validated.
 *   <li>no ISO control characters, no unpaired surrogates, and no bidirectional override or isolate
 *       controls (U+202A–U+202E and U+2066–U+2069), which can visually spoof text
 * </ul>
 *
 * <p>Unicode is otherwise allowed and stored as given. No normalization is applied.
 *
 * @param value the validated text
 */
public record DisplayName(String value) {
  /** Maximum length in Unicode code points. */
  public static final int MAX_CODE_POINTS = 64;

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#DISPLAY_NAME_INVALID}
   */
  public DisplayName {
    Objects.requireNonNull(value, "value");
    String problem = findProblem(value);
    if (problem != null) {
      throw new InvalidDomainValueException(
          DomainErrorCode.DISPLAY_NAME_INVALID,
          "Invalid display name \"" + DomainText.truncateForMessage(value) + "\": " + problem);
    }
  }

  /** Equivalent to the constructor. */
  public static DisplayName of(String value) {
    return new DisplayName(value);
  }

  @Override
  public String toString() {
    return value;
  }

  private static @Nullable String findProblem(String value) {
    return TextRules.findProblem(value, MAX_CODE_POINTS);
  }
}
