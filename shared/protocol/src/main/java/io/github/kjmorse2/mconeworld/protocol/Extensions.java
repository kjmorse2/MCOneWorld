// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * An immutable list of at most {@value ProtocolLimits#MAX_EXTENSIONS} extensions with distinct
 * keys, sorted by key.
 *
 * @param values the extensions, sorted and unmodifiable
 */
public record Extensions(List<Extension> values) {
  /** No extensions: every message of protocol version 1 as written by this implementation. */
  public static final Extensions NONE = new Extensions(List.of());

  /**
   * @throws IllegalArgumentException if there are too many or two share a key
   */
  public Extensions {
    List<Extension> sorted = new ArrayList<>(values);
    sorted.sort(null);
    if (sorted.size() > ProtocolLimits.MAX_EXTENSIONS) {
      throw new IllegalArgumentException(
          "At most " + ProtocolLimits.MAX_EXTENSIONS + " extensions");
    }
    for (int i = 1; i < sorted.size(); i++) {
      if (sorted.get(i - 1).key().equals(sorted.get(i).key())) {
        throw new IllegalArgumentException("Duplicate extension key " + sorted.get(i).key());
      }
    }
    values = List.copyOf(sorted);
  }

  public static Extensions of(Collection<Extension> extensions) {
    return new Extensions(List.copyOf(extensions));
  }
}
