// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.backup;

import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import java.util.Objects;

/**
 * A planned backup, before anything is written.
 *
 * @param locations the world and sidecar
 * @param plan the operation plan the user approves
 * @param worldFiles files copied from the world
 * @param sidecarFiles files copied from the sidecar
 * @param totalBytes bytes copied in all
 * @param requiredBytes free space required on the sidecar's filesystem (the copy plus headroom)
 * @param usableBytes free space available there now
 */
public record BackupPreview(
    ToolLocations locations,
    OperationPlan plan,
    int worldFiles,
    int sidecarFiles,
    long totalBytes,
    long requiredBytes,
    long usableBytes) {
  public BackupPreview {
    Objects.requireNonNull(locations, "locations");
    Objects.requireNonNull(plan, "plan");
  }
}
