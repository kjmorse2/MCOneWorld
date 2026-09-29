// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manifest schema version {@code major.minor}. This is independent of the application release
 * version.
 *
 * <ul>
 *   <li>A <b>major</b> change is incompatible. Readers reject any major they do not implement.
 *   <li>A <b>minor</b> change only adds optional or defaulted members. Readers accept any minor of
 *       a supported major, and preserve members they do not understand (see {@code manifest-v1.md}
 *       §"Compatibility").
 * </ul>
 *
 * @param major incompatible-change counter
 * @param minor compatible-addition counter
 */
public record SchemaVersion(int major, int minor) implements Comparable<SchemaVersion> {
  private static final Pattern TEXT = Pattern.compile("(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})");

  public SchemaVersion {
    if (major < 0 || major > 9999 || minor < 0 || minor > 9999) {
      throw new IllegalArgumentException("Version components must be in 0..9999");
    }
  }

  /**
   * Parses {@code "<major>.<minor>"}, with no leading zeros.
   *
   * @throws IllegalArgumentException if malformed
   */
  public static SchemaVersion parse(String text) {
    Matcher m = TEXT.matcher(text);
    if (!m.matches()) {
      throw new IllegalArgumentException("Malformed schema version");
    }
    return new SchemaVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
  }

  @Override
  public int compareTo(SchemaVersion other) {
    return major != other.major
        ? Integer.compare(major, other.major)
        : Integer.compare(minor, other.minor);
  }

  @Override
  public String toString() {
    return major + "." + minor;
  }
}
