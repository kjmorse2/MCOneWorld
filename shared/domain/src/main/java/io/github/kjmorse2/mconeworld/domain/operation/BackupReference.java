// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.Checksum;
import java.util.Objects;

/**
 * A verified backup, by identity and content checksum. Where the backup is stored is
 * machine-specific and never part of a plan or journal.
 *
 * @param id the backup
 * @param checksum the checksum recorded when the backup was verified
 */
public record BackupReference(BackupId id, Checksum checksum) {
  public BackupReference {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(checksum, "checksum");
  }
}
