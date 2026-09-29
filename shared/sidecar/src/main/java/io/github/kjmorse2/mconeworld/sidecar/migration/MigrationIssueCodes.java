// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/** Stable codes carried by {@link MigrationException}. */
public final class MigrationIssueCodes {
  /** The source is not a recognisable manifest (no {@code format}/{@code schemaVersion}). */
  public static final IssueCode SOURCE_UNREADABLE =
      IssueCode.of("sidecar.migration.source_unreadable");

  /** The source is already readable by the current reader (same major version). */
  public static final IssueCode ALREADY_CURRENT = IssueCode.of("sidecar.migration.already_current");

  /** Migrations can only target the schema the current reader validates. */
  public static final IssueCode INCOMPATIBLE_TARGET =
      IssueCode.of("sidecar.migration.incompatible_target");

  /** No contiguous chain of registered steps leads from source to target. */
  public static final IssueCode NO_PATH = IssueCode.of("sidecar.migration.no_path");

  /** More than one chain leads from source to target; refusing to choose. */
  public static final IssueCode AMBIGUOUS_PATH = IssueCode.of("sidecar.migration.ambiguous_path");

  /** The target is older than the source and no explicit downgrade step exists. */
  public static final IssueCode DOWNGRADE_UNSUPPORTED =
      IssueCode.of("sidecar.migration.downgrade_unsupported");

  /** A registered step is not a single version step, or duplicates another step's id. */
  public static final IssueCode INVALID_STEP = IssueCode.of("sidecar.migration.invalid_step");

  /** A step's prerequisite does not hold for this source. */
  public static final IssueCode PREREQUISITE_FAILED =
      IssueCode.of("sidecar.migration.prerequisite_failed");

  /** The source contains data a step cannot carry forward without loss; refused, never dropped. */
  public static final IssueCode UNSUPPORTED_DATA =
      IssueCode.of("sidecar.migration.unsupported_data");

  /** A step failed or produced output of the wrong version. */
  public static final IssueCode STEP_FAILED = IssueCode.of("sidecar.migration.step_failed");

  /** The migrated manifest does not pass the target reader's validation. */
  public static final IssueCode TARGET_INVALID = IssueCode.of("sidecar.migration.target_invalid");

  /** The source manifest changed between planning and application. */
  public static final IssueCode SOURCE_CHANGED = IssueCode.of("sidecar.migration.source_changed");

  /** Re-running the plan's steps produced different output (a migration is not deterministic). */
  public static final IssueCode NONDETERMINISTIC =
      IssueCode.of("sidecar.migration.nondeterministic");

  /**
   * The backup evidence does not match the source, or no longer verifies, or lies inside the
   * sidecar.
   */
  public static final IssueCode BACKUP_INVALID = IssueCode.of("sidecar.migration.backup_invalid");

  private MigrationIssueCodes() {}
}
