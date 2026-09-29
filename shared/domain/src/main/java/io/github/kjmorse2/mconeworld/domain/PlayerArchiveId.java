// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity of one player archive record (one player's archived season state).
 *
 * <p>Invariant: never the nil UUID. The textual form is always the canonical lowercase UUID. This
 * type deliberately has no random factory. Callers supply the UUID at a platform boundary, which
 * keeps domain code deterministic and testable.
 *
 * <p>Ordering compares the canonical text, so sorted output matches lexicographic file order.
 *
 * @param value the underlying UUID; never nil
 */
public record PlayerArchiveId(UUID value) implements Comparable<PlayerArchiveId> {

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_NIL} for the nil
   *     UUID
   */
  public PlayerArchiveId {
    Objects.requireNonNull(value, "value");
    CanonicalUuids.requireNotNil(value, "PlayerArchiveId");
  }

  /** Wraps an existing UUID; see the constructor for failures. */
  public static PlayerArchiveId of(UUID value) {
    return new PlayerArchiveId(value);
  }

  /**
   * Parses the canonical 36-character lowercase form.
   *
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_MALFORMED} for any
   *     other form, or {@link DomainErrorCode#IDENTIFIER_NIL} for the nil UUID
   */
  public static PlayerArchiveId parse(String text) {
    return new PlayerArchiveId(CanonicalUuids.parse(text, "PlayerArchiveId"));
  }

  @Override
  public int compareTo(PlayerArchiveId other) {
    return value.toString().compareTo(other.value.toString());
  }

  /** Returns the canonical lowercase UUID text, which {@link #parse(String)} accepts. */
  @Override
  public String toString() {
    return value.toString();
  }
}
