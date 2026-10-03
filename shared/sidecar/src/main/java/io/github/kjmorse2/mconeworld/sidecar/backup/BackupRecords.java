// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.backup;

import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.archive.BackupRecord;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupFiles;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarTransaction;
import io.github.kjmorse2.mconeworld.sidecar.storage.StagedFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * Records a verified backup in the sidecar manifest (ADR 0012): stages its {@code backup.json} in
 * {@code tx} and returns {@code base} with the {@link BackupRecord} and its location added. The
 * caller commits; that commit is the backup's commit point. The record's checksum and size are
 * those of {@code backup.json}, which the commit verifies on disk.
 */
public final class BackupRecords {
  private BackupRecords() {}

  /**
   * Stages {@code backup.json} and adds the backup's record.
   *
   * @param verification a clean verification of the backup's payload against its manifest
   * @throws IllegalArgumentException if {@code verification} is not clean
   * @throws IOException if staging fails
   */
  public static ManifestDocument stageAndRecord(
      SidecarTransaction tx,
      ManifestDocument base,
      BackupVerification verification,
      @Nullable OperationId operation,
      @Nullable SeasonId activeSeason)
      throws IOException {
    BackupManifest manifest = verification.manifest();
    if (manifest == null || !verification.verified()) {
      throw new IllegalArgumentException("Only a verified backup can be recorded");
    }
    byte[] bytes = new BackupManifestCodec().encode(manifest);
    StagedFile staged =
        tx.stage(
            BackupFiles.manifestPath(manifest.id()), new ByteArrayInputStream(bytes), bytes.length);
    BackupRecord record =
        new BackupRecord(
            manifest.id(),
            manifest.createdAt(),
            staged.checksum(),
            staged.sizeBytes(),
            operation,
            activeSeason);
    return base.withBackup(record, staged.target());
  }
}
