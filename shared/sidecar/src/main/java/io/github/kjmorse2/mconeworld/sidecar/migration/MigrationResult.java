// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.sidecar.storage.CommitResult;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import java.util.UUID;

/**
 * A committed migration.
 *
 * @param runId unique id of this application
 * @param report what the migration did
 * @param backup the backup evidence that was required
 * @param reportPath where the persisted report lives (also referenced by the new manifest)
 * @param commit the storage commit
 */
public record MigrationResult(
    UUID runId,
    MigrationReport report,
    BackupId backup,
    SidecarPath reportPath,
    CommitResult commit) {}
