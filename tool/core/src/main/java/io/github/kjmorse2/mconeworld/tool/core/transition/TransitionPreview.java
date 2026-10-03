// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.transition;

import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.archive.BackupRecord;
import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A planned season transition, before anything is written.
 *
 * @param locations the world and sidecar
 * @param plan the operation plan the user approves
 * @param request what was asked for
 * @param season the new season's id
 * @param previous the active season it archives, or {@code null} if none is active
 * @param backup the full backup of the world as it is, confirmed before anything changes
 * @param endFiles the End files archived and removed; 0 when the world has no End yet
 * @param endBytes their total size
 * @param endRecords chunk-index records of the End, reported and removed
 * @param protectedEndRecords how many of those are protected (acknowledged in the request)
 */
public record TransitionPreview(
    ToolLocations locations,
    OperationPlan plan,
    TransitionRequest request,
    SeasonId season,
    @Nullable SeasonId previous,
    BackupRecord backup,
    int endFiles,
    long endBytes,
    long endRecords,
    long protectedEndRecords) {
  public TransitionPreview {
    Objects.requireNonNull(locations, "locations");
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(season, "season");
    Objects.requireNonNull(backup, "backup");
  }

  /** Whether the End is archived (the world has End files). */
  public boolean archivesEnd() {
    return endFiles > 0;
  }
}
