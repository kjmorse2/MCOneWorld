// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity of a season, independent of its user-editable display name. Renaming a season
 * never changes its {@code SeasonId}.
 *
 * <p>Invariant: never the nil UUID. The textual form is always the canonical lowercase UUID. This
 * type deliberately has no random factory. Callers that create seasons supply the UUID (for example
 * from {@code UUID.randomUUID()} at a platform boundary), which keeps domain code deterministic and
 * testable.
 *
 * <p>Ordering compares the canonical text, so sorted output matches lexicographic file order.
 *
 * @param value the underlying UUID; never nil
 */
public record SeasonId(UUID value) implements Comparable<SeasonId> {

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_NIL} for the nil
   *     UUID
   */
  public SeasonId {
    Objects.requireNonNull(value, "value");
    CanonicalUuids.requireNotNil(value, "SeasonId");
  }

  /** Wraps an existing UUID; see the constructor for failures. */
  public static SeasonId of(UUID value) {
    return new SeasonId(value);
  }

  /**
   * Parses the canonical 36-character lowercase form.
   *
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_MALFORMED} for any
   *     other form, or {@link DomainErrorCode#IDENTIFIER_NIL} for the nil UUID
   */
  public static SeasonId parse(String text) {
    return new SeasonId(CanonicalUuids.parse(text, "SeasonId"));
  }

  @Override
  public int compareTo(SeasonId other) {
    return value.toString().compareTo(other.value.toString());
  }

  /** Returns the canonical lowercase UUID text, which {@link #parse(String)} accepts. */
  @Override
  public String toString() {
    return value.toString();
  }
}
