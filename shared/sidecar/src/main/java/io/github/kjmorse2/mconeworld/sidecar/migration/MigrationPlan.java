// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.domain.ArchiveId;
import java.util.List;
import java.util.Objects;

/**
 * A validated, not-yet-applied migration. Produced by {@link MigrationPlanner}, which has no side
 * effects. Applying it requires {@link MigrationRunner} and backup evidence.
 *
 * @param report what the migration will do (from the dry run)
 * @param steps the unique chain of steps
 * @param sourceRevision the source manifest's revision (the migrated manifest has this + 1)
 * @param archiveId the archive identity, which the migration preserves
 */
public record MigrationPlan(
    MigrationReport report, List<MigrationStep> steps, long sourceRevision, ArchiveId archiveId) {
  public MigrationPlan {
    Objects.requireNonNull(report, "report");
    Objects.requireNonNull(archiveId, "archiveId");
    steps = List.copyOf(steps);
  }
}
