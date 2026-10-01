// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.TextRules;
import java.util.Objects;
import java.util.regex.Pattern;

/** Grammar checks shared by the namespaced keys of this package. */
final class Keys {
  /**
   * {@code namespace:path}. The namespace is {@code [a-z0-9_.-]+}; the path is one or more {@code
   * [a-z0-9_.-]+} segments separated by {@code /}. Segments {@code .} and {@code ..} are refused by
   * {@link #checkSegments}.
   */
  static final Pattern NAMESPACED = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_.-]+(/[a-z0-9_.-]+)*");

  /** Like {@link #NAMESPACED}, but path segments may also contain upper case. */
  static final Pattern LOGICAL_PATH =
      Pattern.compile("[a-z0-9_.-]+:[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*");

  /** Opaque tokens: extension target keys and precondition fact values. */
  static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._:+-]+");

  private Keys() {}

  static String check(String kind, String value, Pattern grammar, int maxLength) {
    Objects.requireNonNull(value, kind);
    if (value.length() > maxLength || !grammar.matcher(value).matches() || !checkSegments(value)) {
      throw new InvalidDomainValueException(
          DomainErrorCode.KEY_MALFORMED,
          "Malformed " + kind + " \"" + TextRules.excerpt(value) + "\"");
    }
    return value;
  }

  /** Refuses {@code .} and {@code ..} path segments, so a key can never read as traversal. */
  private static boolean checkSegments(String value) {
    int colon = value.indexOf(':');
    String path = colon < 0 ? value : value.substring(colon + 1);
    for (String segment : path.split("/", -1)) {
      if (segment.equals(".") || segment.equals("..")) {
        return false;
      }
    }
    return true;
  }
}
