// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

import java.util.Collection;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * An immutable, sorted set of at most {@value ProtocolLimits#MAX_CAPABILITIES} capabilities. The
 * order does not depend on how the set was built, so encoding is deterministic.
 *
 * @param values the capabilities, sorted and unmodifiable
 */
public record CapabilitySet(SortedSet<Capability> values) {
  /** No capabilities. */
  public static final CapabilitySet EMPTY = new CapabilitySet(new TreeSet<>());

  /**
   * @throws IllegalArgumentException if there are too many
   */
  public CapabilitySet {
    TreeSet<Capability> copy = new TreeSet<>(values);
    if (copy.size() > ProtocolLimits.MAX_CAPABILITIES) {
      throw new IllegalArgumentException(
          "At most " + ProtocolLimits.MAX_CAPABILITIES + " capabilities");
    }
    values = Collections.unmodifiableSortedSet(copy);
  }

  public static CapabilitySet of(Capability... capabilities) {
    return new CapabilitySet(new TreeSet<>(java.util.List.of(capabilities)));
  }

  public static CapabilitySet of(Collection<Capability> capabilities) {
    return new CapabilitySet(new TreeSet<>(capabilities));
  }

  public boolean contains(Capability capability) {
    return values.contains(capability);
  }

  /** The capabilities in both sets. */
  public CapabilitySet intersect(CapabilitySet other) {
    TreeSet<Capability> both = new TreeSet<>(values);
    both.retainAll(other.values);
    return new CapabilitySet(both);
  }

  /** Whether every capability here is also in {@code other}. */
  public boolean isSubsetOf(CapabilitySet other) {
    return other.values.containsAll(values);
  }
}
