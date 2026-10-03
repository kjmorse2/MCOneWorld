// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.backup;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupEntryPath;
import java.util.Objects;

/**
 * One file of a backup.
 *
 * @param path where it lies in the backup, and so where it is restored
 * @param sizeBytes its size; never negative
 * @param checksum its SHA-256
 */
public record BackupEntry(BackupEntryPath path, long sizeBytes, Checksum checksum) {
  public BackupEntry {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(checksum, "checksum");
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("Size must not be negative");
    }
    if (checksum.algorithm() != Checksum.Algorithm.SHA_256) {
      throw new IllegalArgumentException("Backup entries use SHA-256");
    }
  }
}
