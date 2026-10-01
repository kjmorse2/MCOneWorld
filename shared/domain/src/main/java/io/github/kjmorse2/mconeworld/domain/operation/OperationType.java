// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

/**
 * What kind of operation a plan describes, as a namespaced key such as {@code
 * mconeworld:chunk_deletion} or {@code example-plugin:warp_reset}.
 *
 * <p>The namespace makes the set extensible without a central registry: MCOneWorld owns {@code
 * mconeworld:}, and integrations use their own namespace. Shared code attaches no behaviour to a
 * type; platform modules decide which types they can execute and refuse the rest.
 *
 * <p>Grammar: {@code [a-z0-9_.-]+:[a-z0-9_.-]+(/[a-z0-9_.-]+)*}, at most {@value #MAX_LENGTH}
 * characters, and no {@code .} or {@code ..} path segment.
 *
 * @param value the key text
 */
public record OperationType(String value) implements Comparable<OperationType> {
  /** Maximum length in characters. */
  public static final int MAX_LENGTH = 128;

  /**
   * @throws io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException with {@code
   *     KEY_MALFORMED}
   */
  public OperationType {
    Keys.check("operation type", value, Keys.NAMESPACED, MAX_LENGTH);
  }

  /** Equivalent to the constructor. */
  public static OperationType of(String value) {
    return new OperationType(value);
  }

  @Override
  public int compareTo(OperationType other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value;
  }
}
