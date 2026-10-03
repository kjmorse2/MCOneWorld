// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.archive;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A dimension whose files were archived out of the world, so Minecraft generates it afresh (ADR
 * 0010; manifest 1.1). The files live on, verified, in the referenced backup. History only: it
 * grants nothing and changes nothing in the world by itself.
 *
 * @param dimension the archived dimension, for example {@code minecraft:the_end}
 * @param season the season that was active when it was archived (the one that ended), or {@code
 *     null} if none was
 * @param operation the season transition that archived it
 * @param backup the backup holding the archived files
 * @param archivedAt when it was archived
 */
public record ArchivedDimension(
    DimensionKey dimension,
    @Nullable SeasonId season,
    OperationId operation,
    BackupId backup,
    Instant archivedAt) {
  public ArchivedDimension {
    Objects.requireNonNull(dimension, "dimension");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(backup, "backup");
    Objects.requireNonNull(archivedAt, "archivedAt");
  }
}
