// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.sidecar.manifest.SchemaVersion;
import java.util.List;
import java.util.Objects;

/**
 * Description of one migration step.
 *
 * @param id stable identity
 * @param from the schema version it reads
 * @param to the schema version it writes: exactly one version step away (see {@link #isSingleStep})
 * @param prerequisites human-readable conditions the step checks on its input (it fails with {@code
 *     PREREQUISITE_FAILED} or {@code UNSUPPORTED_DATA} otherwise)
 */
public record MigrationStep(
    MigrationId id, SchemaVersion from, SchemaVersion to, List<String> prerequisites) {
  public MigrationStep {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    prerequisites = List.copyOf(prerequisites);
  }

  /**
   * Returns whether this step moves to an older version. Such steps are only used for explicit
   * downgrades.
   */
  public boolean isDowngrade() {
    return to.compareTo(from) < 0;
  }

  /**
   * Returns whether the step moves exactly one version:
   *
   * <ul>
   *   <li>within a major, the minor goes up by one: {@code N.m → N.(m+1)}
   *   <li>across majors, to the first minor: {@code N.m → (N+1).0}
   *   <li>a downgrade is the exact reverse of one of these
   * </ul>
   */
  public boolean isSingleStep() {
    SchemaVersion low = isDowngrade() ? to : from;
    SchemaVersion high = isDowngrade() ? from : to;
    boolean nextMinor = low.major() == high.major() && high.minor() == low.minor() + 1;
    boolean nextMajor = high.major() == low.major() + 1 && high.minor() == 0;
    return nextMinor || nextMajor;
  }
}
