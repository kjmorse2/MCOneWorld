// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.sidecar.manifest.SchemaVersion;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic description of a migration: the same source bytes always produce an equal report.
 *
 * @param source the schema version migrated from
 * @param target the schema version migrated to
 * @param sourceChecksum checksum of the exact source manifest bytes
 * @param migratedChecksum checksum of the migrated manifest bytes, before the report reference is
 *     added
 * @param steps one report per step, in execution order
 */
public record MigrationReport(
    SchemaVersion source,
    SchemaVersion target,
    Checksum sourceChecksum,
    Checksum migratedChecksum,
    List<StepReport> steps) {
  public MigrationReport {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(sourceChecksum, "sourceChecksum");
    Objects.requireNonNull(migratedChecksum, "migratedChecksum");
    steps = List.copyOf(steps);
  }
}
