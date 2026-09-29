// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity of one backup. It is recorded in operation plans so a restore always refers to
 * exactly the backup that was verified.
 *
 * <p>Invariant: never the nil UUID. The textual form is always the canonical lowercase UUID. This
 * type deliberately has no random factory. Callers supply the UUID at a platform boundary, which
 * keeps domain code deterministic and testable.
 *
 * <p>Ordering compares the canonical text, so sorted output matches lexicographic file order.
 *
 * @param value the underlying UUID; never nil
 */
public record BackupId(UUID value) implements Comparable<BackupId> {

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_NIL} for the nil
   *     UUID
   */
  public BackupId {
    Objects.requireNonNull(value, "value");
    CanonicalUuids.requireNotNil(value, "BackupId");
  }

  /** Wraps an existing UUID; see the constructor for failures. */
  public static BackupId of(UUID value) {
    return new BackupId(value);
  }

  /**
   * Parses the canonical 36-character lowercase form.
   *
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_MALFORMED} for any
   *     other form, or {@link DomainErrorCode#IDENTIFIER_NIL} for the nil UUID
   */
  public static BackupId parse(String text) {
    return new BackupId(CanonicalUuids.parse(text, "BackupId"));
  }

  @Override
  public int compareTo(BackupId other) {
    return value.toString().compareTo(other.value.toString());
  }

  /** Returns the canonical lowercase UUID text, which {@link #parse(String)} accepts. */
  @Override
  public String toString() {
    return value.toString();
  }
}
