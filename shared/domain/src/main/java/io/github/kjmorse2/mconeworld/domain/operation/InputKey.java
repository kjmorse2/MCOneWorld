// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

/**
 * A logical, portable name for one input an operation depends on, such as {@code
 * world:region/r.0.-1.mca}, {@code world:level.dat}, or {@code minecraft:data_version}.
 *
 * <p>The namespace names a <em>logical root</em> (for example {@code world}, {@code sidecar}, or
 * {@code minecraft}), never a host directory. The rest is a relative path inside that root. This is
 * how a plan identifies its input world without persisting an absolute path: the platform resolves
 * the logical root against the directories it was given.
 *
 * <p>Grammar: {@code [a-z0-9_.-]+:[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*}, at most {@value #MAX_LENGTH}
 * characters, and no {@code .} or {@code ..} segment. Backslashes, drive letters, and leading
 * slashes cannot occur.
 *
 * @param value the key text
 */
public record InputKey(String value) implements Comparable<InputKey> {
  /** Maximum length in characters. */
  public static final int MAX_LENGTH = 255;

  /**
   * @throws io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException with {@code
   *     KEY_MALFORMED}
   */
  public InputKey {
    Keys.check("input key", value, Keys.LOGICAL_PATH, MAX_LENGTH);
  }

  /** Equivalent to the constructor. */
  public static InputKey of(String value) {
    return new InputKey(value);
  }

  @Override
  public int compareTo(InputKey other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value;
  }
}
