// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A player's existing Minecraft account UUID: their permanent identity.
 *
 * <p>MCOneWorld <b>never</b> creates, replaces, or fabricates player identities, and a season reset
 * keeps the UUID (root AGENTS.md §2, §11). This type therefore has no generating factory. The only
 * way to obtain one is from a UUID that the server or a world file already supplied.
 *
 * @param value the account UUID; never nil
 */
public record PlayerId(UUID value) implements Comparable<PlayerId> {

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#IDENTIFIER_NIL} for the nil
   *     UUID, which no Minecraft account uses
   */
  public PlayerId {
    Objects.requireNonNull(value, "value");
    CanonicalUuids.requireNotNil(value, "PlayerId");
  }

  /** Wraps an existing account UUID. */
  public static PlayerId of(UUID value) {
    return new PlayerId(value);
  }

  /** Parses the canonical lowercase hyphenated form. */
  public static PlayerId parse(String text) {
    return new PlayerId(CanonicalUuids.parse(text, "PlayerId"));
  }

  @Override
  public int compareTo(PlayerId other) {
    return value.toString().compareTo(other.value.toString());
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
