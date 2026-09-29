// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity of one archive portal marker (a sidecar record of an intended controlled-travel
 * endpoint).
 *
 * <p>Invariant: never the nil UUID. The textual form is always the canonical lowercase UUID. This
 * type deliberately has no random factory. Callers supply the UUID at a platform boundary, which
 * keeps domain code deterministic and testable.
 *
 * <p>Ordering compares the canonical text, so sorted output matches lexicographic file order.
 *
 * @param value the underlying UUID; never nil
 */
public record PortalMarkerId(UUID value) implements Comparable<PortalMarkerId> {

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_NIL} for the nil
   *     UUID
   */
  public PortalMarkerId {
    Objects.requireNonNull(value, "value");
    CanonicalUuids.requireNotNil(value, "PortalMarkerId");
  }

  /** Wraps an existing UUID; see the constructor for failures. */
  public static PortalMarkerId of(UUID value) {
    return new PortalMarkerId(value);
  }

  /**
   * Parses the canonical 36-character lowercase form.
   *
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_MALFORMED} for any
   *     other form, or {@link DomainErrorCode#IDENTIFIER_NIL} for the nil UUID
   */
  public static PortalMarkerId parse(String text) {
    return new PortalMarkerId(CanonicalUuids.parse(text, "PortalMarkerId"));
  }

  @Override
  public int compareTo(PortalMarkerId other) {
    return value.toString().compareTo(other.value.toString());
  }

  /** Returns the canonical lowercase UUID text, which {@link #parse(String)} accepts. */
  @Override
  public String toString() {
    return value.toString();
  }
}
