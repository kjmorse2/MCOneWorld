// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity of one planned world or sidecar operation. It is shared by the operation plan,
 * its journal, and every diagnostic that refers to the operation.
 *
 * <p>Invariant: never the nil UUID. The textual form is always the canonical lowercase UUID. This
 * type deliberately has no random factory. Callers supply the UUID at a platform boundary, which
 * keeps domain code deterministic and testable.
 *
 * <p>Ordering compares the canonical text, so sorted output matches lexicographic file order.
 *
 * @param value the underlying UUID; never nil
 */
public record OperationId(UUID value) implements Comparable<OperationId> {

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_NIL} for the nil
   *     UUID
   */
  public OperationId {
    Objects.requireNonNull(value, "value");
    CanonicalUuids.requireNotNil(value, "OperationId");
  }

  /** Wraps an existing UUID; see the constructor for failures. */
  public static OperationId of(UUID value) {
    return new OperationId(value);
  }

  /**
   * Parses the canonical 36-character lowercase form.
   *
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_MALFORMED} for any
   *     other form, or {@link DomainErrorCode#IDENTIFIER_NIL} for the nil UUID
   */
  public static OperationId parse(String text) {
    return new OperationId(CanonicalUuids.parse(text, "OperationId"));
  }

  @Override
  public int compareTo(OperationId other) {
    return value.toString().compareTo(other.value.toString());
  }

  /** Returns the canonical lowercase UUID text, which {@link #parse(String)} accepts. */
  @Override
  public String toString() {
    return value.toString();
  }
}
