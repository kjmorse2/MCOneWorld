// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.archive;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Metadata about one verified backup. A destructive operation refers to one of these before it
 * mutates anything (root AGENTS.md §10).
 *
 * <p>Where the backup bytes live (a sidecar-relative path or external storage) is a sidecar storage
 * concern and deliberately not modelled here.
 *
 * @param id stable identity
 * @param createdAt when the backup was taken, supplied by the caller
 * @param checksum checksum of the backup archive as verified
 * @param sizeBytes size of the backup archive in bytes; never negative
 * @param operation the operation the backup was taken for, or {@code null} for a manual backup
 * @param activeSeason the season that was active when the backup was taken, or {@code null} if none
 */
public record BackupRecord(
    BackupId id,
    Instant createdAt,
    Checksum checksum,
    long sizeBytes,
    @Nullable OperationId operation,
    @Nullable SeasonId activeSeason) {
  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#VALUE_INVALID} if {@code
   *     sizeBytes} is negative
   */
  public BackupRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(checksum, "checksum");
    if (sizeBytes < 0) {
      throw new InvalidDomainValueException(
          DomainErrorCode.VALUE_INVALID, "Backup size must not be negative: " + sizeBytes);
    }
  }
}
