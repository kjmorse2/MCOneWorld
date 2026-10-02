// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

/**
 * The grammar of capability and extension identifiers: {@code namespace:path}, lower-case ASCII
 * letters, digits, {@code _ - .}, with {@code /} separating non-empty path segments, none of them
 * {@code .} or {@code ..}, at most {@value ProtocolLimits#MAX_IDENTIFIER_BYTES} bytes. The same
 * shape as Minecraft identifiers, but fixed here so the wire format never depends on another
 * module's rules.
 */
public final class Identifiers {
  private Identifiers() {}

  /** Whether {@code text} is a valid capability or extension identifier. */
  public static boolean isValid(String text) {
    if (text.isEmpty() || text.length() > ProtocolLimits.MAX_IDENTIFIER_BYTES) {
      return false;
    }
    int colon = text.indexOf(':');
    if (colon <= 0 || colon == text.length() - 1) {
      return false;
    }
    return validSegments(text.substring(0, colon), false)
        && validSegments(text.substring(colon + 1), true);
  }

  private static boolean validSegments(String part, boolean slashes) {
    String[] segments = slashes ? part.split("/", -1) : new String[] {part};
    for (String segment : segments) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return false;
      }
      for (int i = 0; i < segment.length(); i++) {
        char c = segment.charAt(i);
        boolean ok =
            (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
        if (!ok) {
          return false;
        }
      }
    }
    return true;
  }
}
