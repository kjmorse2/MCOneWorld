// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable identity of one migration step, for example {@code manifest-0.9-to-1.0}. It is recorded in
 * persisted migration reports, so it is never renamed.
 *
 * @param value {@code [a-z0-9][a-z0-9.-]{0,63}}
 */
public record MigrationId(String value) implements Comparable<MigrationId> {
  private static final Pattern GRAMMAR = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");

  public MigrationId {
    Objects.requireNonNull(value, "value");
    if (!GRAMMAR.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid migration id: " + value);
    }
  }

  @Override
  public int compareTo(MigrationId other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value;
  }
}
