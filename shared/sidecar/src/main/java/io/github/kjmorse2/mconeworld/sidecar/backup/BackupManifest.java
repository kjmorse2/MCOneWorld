// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.backup;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupEntryPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The content of a backup's {@code backup.json} ({@code backup-v1.md}).
 *
 * @param id the backup's id, which is also its directory name
 * @param kind full, or one archived dimension
 * @param createdAt when the copy was made
 * @param dimension the archived dimension, for {@link BackupKind#DIMENSION_ARCHIVE} only
 * @param sourceDataVersion the world's data version when copied, or {@code null} if unknown
 * @param sidecarRevision the sidecar manifest revision when copied, or {@code null} if the backup
 *     holds no sidecar files
 * @param entries every file, sorted by path, paths unique
 */
public record BackupManifest(
    BackupId id,
    BackupKind kind,
    Instant createdAt,
    @Nullable DimensionKey dimension,
    @Nullable Integer sourceDataVersion,
    @Nullable Long sidecarRevision,
    List<BackupEntry> entries) {

  /**
   * @throws IllegalArgumentException if paths repeat, the dimension does not fit the kind, or an
   *     archive holds sidecar files
   */
  public BackupManifest {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(createdAt, "createdAt");
    List<BackupEntry> sorted = new ArrayList<>(entries);
    sorted.sort(Comparator.comparing(BackupEntry::path));
    for (int i = 1; i < sorted.size(); i++) {
      if (sorted.get(i).path().equals(sorted.get(i - 1).path())) {
        throw new IllegalArgumentException("Duplicate entry " + sorted.get(i).path());
      }
    }
    entries = List.copyOf(sorted);
    if ((kind == BackupKind.DIMENSION_ARCHIVE) != (dimension != null)) {
      throw new IllegalArgumentException("Only a dimension archive names a dimension, and it must");
    }
    if (kind == BackupKind.DIMENSION_ARCHIVE
        && entries.stream().anyMatch(e -> e.path().area().equals(BackupEntryPath.SIDECAR))) {
      throw new IllegalArgumentException("A dimension archive holds world files only");
    }
    if (sourceDataVersion != null && sourceDataVersion < 0) {
      throw new IllegalArgumentException("Data version must not be negative");
    }
    if (sidecarRevision != null && sidecarRevision < 0) {
      throw new IllegalArgumentException("Revision must not be negative");
    }
  }

  /** The total size of every entry. */
  public long totalBytes() {
    long total = 0;
    for (BackupEntry entry : entries) {
      total = Math.addExact(total, entry.sizeBytes());
    }
    return total;
  }
}
