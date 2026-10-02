// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

/**
 * An inclusive range of protocol versions, {@code 1 <= min <= max <= 65535}.
 *
 * @param min lowest supported version
 * @param max highest supported version
 */
public record VersionRange(int min, int max) {
  /** Protocol version 1, the only version defined so far. */
  public static final int V1 = 1;

  /**
   * @throws IllegalArgumentException if the range is empty or out of bounds
   */
  public VersionRange {
    if (min < 1 || max < min || max > ProtocolLimits.MAX_PROTOCOL_VERSION) {
      throw new IllegalArgumentException("Invalid protocol version range " + min + ".." + max);
    }
  }

  /** The single version {@code version}. */
  public static VersionRange only(int version) {
    return new VersionRange(version, version);
  }

  public boolean contains(int version) {
    return version >= min && version <= max;
  }

  /** The highest version both ranges contain, or 0 if they do not overlap. */
  public int highestCommon(VersionRange other) {
    int high = Math.min(max, other.max);
    return high >= Math.max(min, other.min) ? high : 0;
  }
}
