// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.sidecar.manifest.DecodedManifest;
import io.github.kjmorse2.mconeworld.sidecar.manifest.FileReference;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestLimits;
import io.github.kjmorse2.mconeworld.sidecar.storage.CommitResult;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarTransaction;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StagedFile;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Applies a {@link MigrationPlan}. Only an explicit call does this; nothing else ever migrates.
 *
 * <p>Order of checks. Every check runs before anything is written, so every failure leaves the
 * source untouched.
 *
 * <ol>
 *   <li>The backup copy still verifies, is byte-identical to the planned source, and lies outside
 *       the sidecar.
 *   <li>The current manifest is still byte-identical to the planned source ({@code SOURCE_CHANGED}
 *       otherwise).
 *   <li>Re-running the steps reproduces the planned report exactly ({@code NONDETERMINISTIC}
 *       otherwise), and the output validates.
 *   <li>The storage transaction stages the report, then commits the migrated manifest, which
 *       references the report. The source's checksum is the transaction's precondition.
 * </ol>
 */
public final class MigrationRunner {
  private final MigrationPlanner planner;
  private final ManifestLimits limits;

  public MigrationRunner(MigrationRegistry registry, ManifestLimits limits) {
    this.planner = new MigrationPlanner(registry, limits);
    this.limits = limits;
  }

  /**
   * Applies {@code plan} with {@code writer}, which holds the sidecar lock.
   *
   * @throws MigrationException {@code BACKUP_INVALID}, {@code SOURCE_CHANGED}, {@code
   *     NONDETERMINISTIC}, or {@code TARGET_INVALID}
   * @throws StorageException if the storage commit fails. The source manifest is then unchanged.
   */
  public MigrationResult apply(SidecarWriter writer, MigrationPlan plan, VerifiedBackup backup)
      throws MigrationException, IOException {
    Path sidecar = writer.store().root().path();
    verifyBackup(backup, plan, sidecar);

    byte[] source = writer.store().readManifestBytes();
    if (!MigrationPlanner.measure(source).equals(plan.report().sourceChecksum())) {
      throw new MigrationException(
          MigrationIssueCodes.SOURCE_CHANGED,
          "The manifest changed after the migration was planned; plan again");
    }
    DecodedManifest migrated = planner.migrate(source, plan);

    UUID runId = UUID.randomUUID();
    byte[] reportBytes = MigrationReportFormat.encode(plan.report(), runId, backup);
    SidecarPath reportPath = SidecarPath.of("reports/migrations/" + runId + ".json");

    SidecarTransaction tx =
        writer.beginSchemaMigration(
            plan.report().sourceChecksum(), plan.sourceRevision(), plan.archiveId());
    StagedFile staged =
        tx.stage(reportPath, new ByteArrayInputStream(reportBytes), reportBytes.length);
    ManifestDocument next =
        migrated
            .document()
            .withFile(
                new FileReference(
                    "migration-report",
                    null,
                    staged.target(),
                    staged.checksum(),
                    staged.sizeBytes()));
    CommitResult commit = tx.commit(next);
    return new MigrationResult(runId, plan.report(), backup.id(), reportPath, commit);
  }

  private void verifyBackup(VerifiedBackup backup, MigrationPlan plan, Path sidecar)
      throws MigrationException {
    if (backup.location().startsWith(sidecar)) {
      throw new MigrationException(
          MigrationIssueCodes.BACKUP_INVALID,
          "The backup copy lies inside the sidecar it protects; store it elsewhere");
    }
    if (!backup.checksum().equals(plan.report().sourceChecksum())) {
      throw new MigrationException(
          MigrationIssueCodes.BACKUP_INVALID,
          "The backup is not a copy of the manifest being migrated");
    }
    try {
      VerifiedBackup again =
          VerifiedBackup.verify(backup.id(), backup.location(), limits.maxDocumentBytes());
      if (!again.checksum().equals(backup.checksum())) {
        throw new MigrationException(
            MigrationIssueCodes.BACKUP_INVALID, "The backup copy changed since it was verified");
      }
    } catch (IOException e) {
      throw new MigrationException(
          MigrationIssueCodes.BACKUP_INVALID,
          "The backup copy can no longer be read: " + e.getMessage());
    }
  }
}
