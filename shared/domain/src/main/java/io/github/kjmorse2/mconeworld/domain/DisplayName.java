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

  // Bidirectional embedding/override (U+202A..U+202E) and isolate (U+2066..U+2069) controls.
  // Written numerically so no directionality character ever appears in source.
  private static final int BIDI_EMBEDDING_FIRST = 0x202A;
  private static final int BIDI_EMBEDDING_LAST = 0x202E;
  private static final int BIDI_ISOLATE_FIRST = 0x2066;
  private static final int BIDI_ISOLATE_LAST = 0x2069;

  private static boolean isBidiControl(char c) {
    return (c >= BIDI_EMBEDDING_FIRST && c <= BIDI_EMBEDDING_LAST)
        || (c >= BIDI_ISOLATE_FIRST && c <= BIDI_ISOLATE_LAST);
  }

  private static @Nullable String findProblem(String value) {
    if (value.isEmpty()) {
      return "empty";
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
          return "unpaired surrogate";
        }
        i++;
      } else if (Character.isLowSurrogate(c)) {
        return "unpaired surrogate";
      } else if (Character.isISOControl(c)) {
        return "control character";
      } else if (isBidiControl(c)) {
        return "bidirectional control character";
      }
    }
    int codePoints = value.codePointCount(0, value.length());
    if (codePoints > MAX_CODE_POINTS) {
      return "longer than " + MAX_CODE_POINTS + " code points";
    }
    if (Character.isWhitespace(value.codePointAt(0))
        || Character.isSpaceChar(value.codePointAt(0))
        || Character.isWhitespace(value.codePointBefore(value.length()))
        || Character.isSpaceChar(value.codePointBefore(value.length()))) {
      return "leading or trailing whitespace";
    }
    return null;
  }
}
