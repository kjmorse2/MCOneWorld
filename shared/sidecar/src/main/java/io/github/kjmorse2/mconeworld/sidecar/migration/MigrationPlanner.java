// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader;
import io.github.kjmorse2.mconeworld.sidecar.manifest.DecodedManifest;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestCodec;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestException;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestLimits;
import io.github.kjmorse2.mconeworld.sidecar.manifest.SchemaVersion;
import io.github.kjmorse2.mconeworld.sidecar.storage.Checksums;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans migrations. This is <b>side-effect free</b>: it reads bytes, performs a full dry run in
 * memory, and validates the result with the current reader. It never writes anything.
 */
public final class MigrationPlanner {
  private final MigrationRegistry registry;
  private final ManifestCodec codec;
  private final JsonReader probeReader;

  public MigrationPlanner(MigrationRegistry registry, ManifestLimits limits) {
    this.registry = registry;
    this.codec = new ManifestCodec(limits);
    this.probeReader = SchemaProbe.reader(limits);
  }

  /** Plans a migration of {@code store}'s manifest to {@code target}. Read-only. */
  public MigrationPlan plan(SidecarStore store, SchemaVersion target)
      throws IOException, MigrationException {
    return plan(store.readManifestBytes(), target);
  }

  /**
   * Plans a migration of the manifest {@code source} to {@code target}. The array is not modified.
   *
   * @throws MigrationException with a code from {@link MigrationIssueCodes}
   */
  public MigrationPlan plan(byte[] source, SchemaVersion target) throws MigrationException {
    return dryRun(source, target).plan();
  }

  /** A plan together with the migrated manifest it was derived from. */
  private record DryRun(MigrationPlan plan, DecodedManifest migrated) {}

  private DryRun dryRun(byte[] source, SchemaVersion target) throws MigrationException {
    if (!target.equals(ManifestCodec.CURRENT)) {
      throw new MigrationException(
          MigrationIssueCodes.INCOMPATIBLE_TARGET,
          "Only "
              + ManifestCodec.CURRENT
              + " can be validated by this reader; cannot migrate to "
              + target);
    }
    SchemaVersion from = SchemaProbe.versionOf(source, probeReader);
    if (from.major() == target.major() && from.compareTo(target) >= 0) {
      throw new MigrationException(
          MigrationIssueCodes.ALREADY_CURRENT,
          "Schema "
              + from
              + " is read directly by the "
              + target
              + " reader; no migration is needed");
    }
    List<ManifestMigration> path = registry.findPath(from, target);

    List<StepReport> reports = new ArrayList<>();
    byte[] current = source.clone();
    for (ManifestMigration migration : path) {
      MigrationStep step = migration.step();
      MigrationRecorder recorder = new MigrationRecorder(step);
      byte[] output;
      try {
        output = migration.migrate(current.clone(), recorder);
      } catch (MigrationException e) {
        List<StepReport> partial = new ArrayList<>(reports);
        partial.addAll(e.partialReport());
        throw new MigrationException(
            e.code(),
            e.getMessage() == null ? step.id().value() : e.getMessage(),
            partial,
            e.validation());
      } catch (RuntimeException e) {
        throw new MigrationException(
            MigrationIssueCodes.STEP_FAILED, step.id() + " failed: " + e, reports);
      }
      reports.add(recorder.report());
      SchemaVersion produced = SchemaProbe.versionOf(output, probeReader);
      if (!produced.equals(step.to())) {
        throw new MigrationException(
            MigrationIssueCodes.STEP_FAILED,
            step.id() + " produced schema " + produced + " instead of " + step.to(),
            reports);
      }
      current = output;
    }

    DecodedManifest migrated;
    try {
      migrated = codec.decode(current);
    } catch (ManifestException e) {
      throw new MigrationException(
          MigrationIssueCodes.TARGET_INVALID,
          "The migrated manifest fails " + target + " validation: " + e.getMessage(),
          reports,
          e.issues());
    }
    long revision = migrated.document().revision();
    if (revision < 1) {
      throw new MigrationException(
          MigrationIssueCodes.STEP_FAILED,
          "A migration must increase the revision; the result has revision " + revision,
          reports);
    }
    MigrationReport report =
        new MigrationReport(from, target, measure(source), measure(current), reports);
    return new DryRun(
        new MigrationPlan(
            report,
            path.stream().map(ManifestMigration::step).toList(),
            revision - 1,
            migrated.document().archiveId()),
        migrated);
  }

  /**
   * Re-runs the migration on {@code source} and returns the migrated manifest, provided the result
   * is exactly what {@code plan} predicted.
   *
   * @throws MigrationException {@code NONDETERMINISTIC} if the report or steps differ
   */
  DecodedManifest migrate(byte[] source, MigrationPlan plan) throws MigrationException {
    DryRun again = dryRun(source, plan.report().target());
    if (!again.plan().equals(plan)) {
      throw new MigrationException(
          MigrationIssueCodes.NONDETERMINISTIC,
          "Re-running the migration produced a different result than planned");
    }
    return again.migrated();
  }

  static Checksum measure(byte[] bytes) {
    try {
      return Checksums.sha256(new ByteArrayInputStream(bytes), bytes.length).checksum();
    } catch (IOException e) {
      throw new IllegalStateException("In-memory stream cannot fail", e);
    }
  }
}
