// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

/**
 * A namespaced key naming a step action, a validation check, or an extension target kind, such as
 * {@code mconeworld:delete_region_files} or {@code mconeworld:verify_region_checksums}.
 *
 * <p>It is a <em>descriptor</em>, never executable: shared code only records and compares it.
 * Grammar as {@link OperationType}.
 *
 * @param value the key text
 */
public record ActionKey(String value) implements Comparable<ActionKey> {
  /** Maximum length in characters. */
  public static final int MAX_LENGTH = 128;

  /**
   * @throws io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException with {@code
   *     KEY_MALFORMED}
   */
  public ActionKey {
    Keys.check("action key", value, Keys.NAMESPACED, MAX_LENGTH);
  }

  /** Equivalent to the constructor. */
  public static ActionKey of(String value) {
    return new ActionKey(value);
  }

  @Override
  public int compareTo(ActionKey other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value;
  }
}
