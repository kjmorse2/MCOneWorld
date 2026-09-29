// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity of one protected region.
 *
 * <p>Invariant: never the nil UUID. The textual form is always the canonical lowercase UUID. This
 * type deliberately has no random factory. Callers supply the UUID at a platform boundary, which
 * keeps domain code deterministic and testable.
 *
 * <p>Ordering compares the canonical text, so sorted output matches lexicographic file order.
 *
 * @param value the underlying UUID; never nil
 */
public record ProtectedRegionId(UUID value) implements Comparable<ProtectedRegionId> {

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_NIL} for the nil
   *     UUID
   */
  public ProtectedRegionId {
    Objects.requireNonNull(value, "value");
    CanonicalUuids.requireNotNil(value, "ProtectedRegionId");
  }

  /** Wraps an existing UUID; see the constructor for failures. */
  public static ProtectedRegionId of(UUID value) {
    return new ProtectedRegionId(value);
  }

  /**
   * Parses the canonical 36-character lowercase form.
   *
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_MALFORMED} for any
   *     other form, or {@link DomainErrorCode#IDENTIFIER_NIL} for the nil UUID
   */
  public static ProtectedRegionId parse(String text) {
    return new ProtectedRegionId(CanonicalUuids.parse(text, "ProtectedRegionId"));
  }

  @Override
  public int compareTo(ProtectedRegionId other) {
    return value.toString().compareTo(other.value.toString());
  }

  /** Returns the canonical lowercase UUID text, which {@link #parse(String)} accepts. */
  @Override
  public String toString() {
    return value.toString();
  }
}
