// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import java.util.UUID;

/**
 * Strict parsing shared by the UUID-backed identifier types.
 *
 * <p>{@link UUID#fromString(String)} is lenient (it accepts {@code "1-1-1-1-1"} and upper case).
 * Persistent formats need exactly one textual form per identifier so that files diff and checksum
 * deterministically, so only the canonical 36-character lowercase form is accepted.
 */
final class CanonicalUuids {
  private static final UUID NIL = new UUID(0L, 0L);

  private CanonicalUuids() {}

  static UUID parse(String text, String typeName) {
    if (text.length() != 36) {
      throw malformed(text, typeName);
    }
    UUID uuid;
    try {
      uuid = UUID.fromString(text);
    } catch (IllegalArgumentException e) {
      throw malformed(text, typeName);
    }
    if (!uuid.toString().equals(text)) {
      throw malformed(text, typeName);
    }
    return requireNotNil(uuid, typeName);
  }

  static UUID requireNotNil(UUID uuid, String typeName) {
    if (NIL.equals(uuid)) {
      throw new InvalidDomainValueException(
          DomainErrorCode.IDENTIFIER_NIL, typeName + " must not be the nil UUID");
    }
    return uuid;
  }

  private static InvalidDomainValueException malformed(String text, String typeName) {
    return new InvalidDomainValueException(
        DomainErrorCode.IDENTIFIER_MALFORMED,
        typeName
            + " must be a canonical lowercase hyphenated UUID, got \""
            + DomainText.truncateForMessage(text)
            + "\"");
  }
}
