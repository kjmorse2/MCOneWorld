// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import java.util.Objects;

/**
 * A namespaced key such as {@code mconeworld:global_strongholds}, used for feature records and for
 * {@code extensions} member names. It uses exactly the {@link DimensionKey} grammar: a mandatory
 * namespace, and Minecraft identifier characters.
 *
 * @param value the canonical {@code namespace:path} text
 */
public record FeatureKey(String value) implements Comparable<FeatureKey> {
  /**
   * @throws InvalidDomainValueException with {@code VALUE_INVALID} if not a valid namespaced key
   */
  public FeatureKey {
    Objects.requireNonNull(value, "value");
    if (!DimensionKey.isValid(value)) {
      throw new InvalidDomainValueException(
          DomainErrorCode.VALUE_INVALID, "Invalid namespaced key");
    }
  }

  /** Returns whether {@code value} is a valid key. */
  public static boolean isValid(String value) {
    return DimensionKey.isValid(value);
  }

  @Override
  public int compareTo(FeatureKey other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value;
  }
}
