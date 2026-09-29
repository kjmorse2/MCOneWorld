// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity of one MCOneWorld archive: a sidecar together with the world it describes. It is
 * created once, when MCOneWorld is first set up for a world, and never changes afterwards.
 * Operation plans record it, so a plan can never be applied to a different world's sidecar.
 *
 * <p>Invariant: never the nil UUID. The textual form is always the canonical lowercase UUID. This
 * type deliberately has no random factory. Callers supply the UUID at a platform boundary, which
 * keeps domain code deterministic and testable.
 *
 * <p>Ordering compares the canonical text, so sorted output matches lexicographic file order.
 *
 * @param value the underlying UUID; never nil
 */
public record ArchiveId(UUID value) implements Comparable<ArchiveId> {

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_NIL} for the nil
   *     UUID
   */
  public ArchiveId {
    Objects.requireNonNull(value, "value");
    CanonicalUuids.requireNotNil(value, "ArchiveId");
  }

  /** Wraps an existing UUID; see the constructor for failures. */
  public static ArchiveId of(UUID value) {
    return new ArchiveId(value);
  }

  /**
   * Parses the canonical 36-character lowercase form.
   *
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_MALFORMED} for any
   *     other form, or {@link DomainErrorCode#IDENTIFIER_NIL} for the nil UUID
   */
  public static ArchiveId parse(String text) {
    return new ArchiveId(CanonicalUuids.parse(text, "ArchiveId"));
  }

  @Override
  public int compareTo(ArchiveId other) {
    return value.toString().compareTo(other.value.toString());
  }

  /** Returns the canonical lowercase UUID text, which {@link #parse(String)} accepts. */
  @Override
  public String toString() {
    return value.toString();
  }
}
