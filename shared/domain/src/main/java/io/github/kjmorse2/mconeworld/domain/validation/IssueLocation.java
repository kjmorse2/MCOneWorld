// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.validation;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Logical location of a validation issue within a record tree. It is JSONPath-like and independent
 * of any file format, for example {@code $.seasons[3f2504e0-…].origins[minecraft:the_end]}.
 *
 * <p>Locations are built only through {@link #root()}, {@link #field(String)}, {@link
 * #key(String)}, and {@link #index(int)}, so every location in a report has one consistent spelling
 * and sorts deterministically. Keys are stable identifiers, never display names: names are not
 * unique. In a key, {@code ]} and {@code \} are escaped with a backslash.
 *
 * @param path the rendered location text
 */
public record IssueLocation(String path) implements Comparable<IssueLocation> {
  private static final Pattern FIELD = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*");
  private static final IssueLocation ROOT = new IssueLocation("$");

  /** Validates only that the text is a location this class could have produced. */
  public IssueLocation {
    Objects.requireNonNull(path, "path");
    if (!path.startsWith("$")) {
      throw new IllegalArgumentException("Issue locations start at $: " + path);
    }
  }

  /** The root of the validated structure, {@code $}. */
  public static IssueLocation root() {
    return ROOT;
  }

  /**
   * Appends a named field: {@code $.seasons}.
   *
   * @throws IllegalArgumentException if {@code name} is not an identifier-like field name
   */
  public IssueLocation field(String name) {
    if (!FIELD.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid field name: " + name);
    }
    return new IssueLocation(path + "." + name);
  }

  /** Appends a keyed element, such as a record id or a dimension key: {@code $.seasons[<id>]}. */
  public IssueLocation key(String key) {
    return new IssueLocation(path + "[" + key.replace("\\", "\\\\").replace("]", "\\]") + "]");
  }

  /** Appends a positional element: {@code $.activity[2]}. */
  public IssueLocation index(int index) {
    if (index < 0) {
      throw new IllegalArgumentException("Negative index: " + index);
    }
    return new IssueLocation(path + "[" + index + "]");
  }

  @Override
  public int compareTo(IssueLocation other) {
    return path.compareTo(other.path);
  }

  @Override
  public String toString() {
    return path;
  }
}
