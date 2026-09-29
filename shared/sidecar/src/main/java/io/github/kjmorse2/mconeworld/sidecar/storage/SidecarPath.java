// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A normalized, portable path relative to the sidecar root, for example {@code
 * indexes/overworld.mcoi}. This is the only path form stored in sidecar files. Absolute and
 * machine-specific paths are unrepresentable (root AGENTS.md §9).
 *
 * <p>The rules are syntactic and deliberately strict, so the same text means the same file on every
 * operating system:
 *
 * <ul>
 *   <li>1 to {@value #MAX_SEGMENTS} segments separated by {@code /}; total length at most {@value
 *       #MAX_LENGTH} characters
 *   <li>each segment is 1 to 64 characters of {@code [a-z0-9._-]}. Lowercase only, so paths cannot
 *       collide on case-insensitive filesystems.
 *   <li>no {@code .} or {@code ..} segments and no empty segments, which also excludes a leading
 *       {@code /}, a trailing {@code /}, and {@code //}
 *   <li>no segment ending in {@code .}, and no Windows reserved device names ({@code con}, {@code
 *       nul}, {@code com1}, …, with any extension)
 * </ul>
 *
 * <p>Backslashes, drive letters ({@code c:}), and any other character are rejected by the character
 * rule. Resolving a path against a real directory, including symlink-escape checks, is done by
 * storage code at use time, because syntax alone cannot see symlinks.
 *
 * @param value the canonical path text
 */
public record SidecarPath(String value) implements Comparable<SidecarPath> {
  /** Maximum total length in characters. */
  public static final int MAX_LENGTH = 255;

  /** Maximum number of segments. */
  public static final int MAX_SEGMENTS = 16;

  private static final int MAX_SEGMENT_LENGTH = 64;
  private static final Set<String> WINDOWS_RESERVED =
      Set.of(
          "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7",
          "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

  /**
   * @throws UnsafeSidecarPathException if {@code value} violates any rule above
   */
  public SidecarPath {
    Objects.requireNonNull(value, "value");
    String problem = findProblem(value);
    if (problem != null) {
      throw new UnsafeSidecarPathException(value, problem);
    }
  }

  /** Equivalent to the constructor. */
  public static SidecarPath of(String value) {
    return new SidecarPath(value);
  }

  /** Returns whether {@code value} is a valid sidecar path. */
  public static boolean isValid(String value) {
    return findProblem(value) == null;
  }

  /** The path's segments, in order. */
  public List<String> segments() {
    return List.of(value.split("/"));
  }

  /**
   * Returns whether this path lies under the directory {@code prefix}, for example {@code backups}.
   */
  public boolean isUnder(String directory) {
    return value.startsWith(directory + "/");
  }

  @Override
  public int compareTo(SidecarPath other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value;
  }

  private static @Nullable String findProblem(String value) {
    if (value.isEmpty()) {
      return "empty";
    }
    if (value.length() > MAX_LENGTH) {
      return "longer than " + MAX_LENGTH + " characters";
    }
    String[] segments = value.split("/", -1);
    if (segments.length > MAX_SEGMENTS) {
      return "more than " + MAX_SEGMENTS + " segments";
    }
    for (String segment : segments) {
      if (segment.isEmpty()) {
        return "empty segment (absolute path, trailing slash, or //)";
      }
      if (segment.equals(".") || segment.equals("..")) {
        return "'" + segment + "' segments are not allowed";
      }
      if (segment.length() > MAX_SEGMENT_LENGTH) {
        return "segment longer than " + MAX_SEGMENT_LENGTH + " characters";
      }
      for (int i = 0; i < segment.length(); i++) {
        char c = segment.charAt(i);
        boolean allowed =
            (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
        if (!allowed) {
          return "character '" + printable(c) + "' is not allowed (only a-z 0-9 . _ -)";
        }
      }
      if (segment.endsWith(".")) {
        return "segment ends with '.'";
      }
      String base = segment.contains(".") ? segment.substring(0, segment.indexOf('.')) : segment;
      if (WINDOWS_RESERVED.contains(base.toLowerCase(Locale.ROOT))) {
        return "'" + segment + "' is a reserved device name on Windows";
      }
    }
    return null;
  }

  private static String printable(char c) {
    return c < 0x20 || c > 0x7E ? String.format(Locale.ROOT, "U+%04X", (int) c) : String.valueOf(c);
  }
}
