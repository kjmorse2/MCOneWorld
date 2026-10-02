// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

import java.util.Objects;

/**
 * A named optional feature, such as {@code mconeworld:season_context}. Peers advertise what they
 * support; only the intersection is used, and an unknown capability is simply never enabled.
 *
 * @param id a namespaced identifier (see {@link Identifiers})
 */
public record Capability(String id) implements Comparable<Capability> {
  /** The client wants, and the server provides, the active season context. Since version 1. */
  public static final Capability SEASON_CONTEXT = new Capability("mconeworld:season_context");

  /**
   * @throws IllegalArgumentException if {@code id} breaks the identifier grammar
   */
  public Capability {
    Objects.requireNonNull(id, "id");
    if (!Identifiers.isValid(id)) {
      throw new IllegalArgumentException("Invalid capability id: " + id);
    }
  }

  /** Ordered by id, byte for byte (the ids are ASCII), which is the wire order. */
  @Override
  public int compareTo(Capability other) {
    return id.compareTo(other.id);
  }

  @Override
  public String toString() {
    return id;
  }
}
