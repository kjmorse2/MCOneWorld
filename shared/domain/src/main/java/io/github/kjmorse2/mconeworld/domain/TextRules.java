// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import org.jspecify.annotations.Nullable;

/**
 * The shared rules for bounded, human-readable text that is shown in chat, UIs, logs, and reports:
 * {@link DisplayName} and the operation texts of {@code domain.operation}.
 *
 * <p>Text is checked, never silently repaired:
 *
 * <ul>
 *   <li>1 to {@code maxCodePoints} Unicode code points
 *   <li>no leading or trailing whitespace (rejected rather than trimmed, so the stored value is
 *       exactly what was validated)
 *   <li>no ISO control characters (so no line breaks: one value is one log line), no unpaired
 *       surrogates, and no bidirectional embedding, override, or isolate controls (U+202A–U+202E
 *       and U+2066–U+2069), which can visually spoof text
 * </ul>
 *
 * <p>Unicode is otherwise allowed and stored as given. No normalization is applied.
 */
public final class TextRules {
  // Written numerically so no directionality character ever appears in source.
  private static final int BIDI_EMBEDDING_FIRST = 0x202A;
  private static final int BIDI_EMBEDDING_LAST = 0x202E;
  private static final int BIDI_ISOLATE_FIRST = 0x2066;
  private static final int BIDI_ISOLATE_LAST = 0x2069;

  private TextRules() {}

  /**
   * Returns a short description of the first rule {@code value} breaks, or {@code null} if it is
   * acceptable. The description never echoes the value.
   */
  public static @Nullable String findProblem(String value, int maxCodePoints) {
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
    if (codePoints > maxCodePoints) {
      return "longer than " + maxCodePoints + " code points";
    }
    if (isSpace(value.codePointAt(0)) || isSpace(value.codePointBefore(value.length()))) {
      return "leading or trailing whitespace";
    }
    return null;
  }

  /** Bounds how much of an untrusted string reaches an exception message. */
  public static String excerpt(String untrusted) {
    return DomainText.truncateForMessage(untrusted);
  }

  private static boolean isSpace(int codePoint) {
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
  }

  private static boolean isBidiControl(char c) {
    return (c >= BIDI_EMBEDDING_FIRST && c <= BIDI_EMBEDDING_LAST)
        || (c >= BIDI_ISOLATE_FIRST && c <= BIDI_ISOLATE_LAST);
  }
}
