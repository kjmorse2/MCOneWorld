// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.archive;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/**
 * Stable issue codes emitted by {@link ArchiveState#validate()}. They are a public contract, pinned
 * by tests: never rename or repurpose one.
 */
public final class ArchiveIssueCodes {
  /** ERROR: two seasons share an id. */
  public static final IssueCode SEASON_DUPLICATE_ID = IssueCode.of("archive.season.duplicate_id");

  /** ERROR: the active-season reference names no existing season. */
  public static final IssueCode ACTIVE_SEASON_NOT_FOUND =
      IssueCode.of("archive.active_season.not_found");

  /** ERROR: the referenced active season does not have status ACTIVE. */
  public static final IssueCode ACTIVE_SEASON_NOT_ACTIVE =
      IssueCode.of("archive.active_season.not_active");

  /** ERROR: a season has status ACTIVE but is not the referenced active season. */
  public static final IssueCode ACTIVE_SEASON_UNREFERENCED =
      IssueCode.of("archive.active_season.unreferenced");

  /** ERROR: more than one season has status ACTIVE. */
  public static final IssueCode ACTIVE_SEASON_MULTIPLE =
      IssueCode.of("archive.active_season.multiple");

  /** ERROR: two protected regions share an id. */
  public static final IssueCode PROTECTED_REGION_DUPLICATE_ID =
      IssueCode.of("archive.protected_region.duplicate_id");

  /** ERROR: two portal markers share an id. */
  public static final IssueCode PORTAL_MARKER_DUPLICATE_ID =
      IssueCode.of("archive.portal_marker.duplicate_id");

  /** ERROR: two backups share an id. */
  public static final IssueCode BACKUP_DUPLICATE_ID = IssueCode.of("archive.backup.duplicate_id");

  /** ERROR: two player archive records share an id. */
  public static final IssueCode PLAYER_ARCHIVE_DUPLICATE_ID =
      IssueCode.of("archive.player_archive.duplicate_id");

  /** ERROR: a record refers to a season that does not exist. */
  public static final IssueCode UNKNOWN_SEASON_REFERENCE =
      IssueCode.of("archive.reference.unknown_season");

  /** ERROR: a record refers to a backup that does not exist (manifest 1.1). */
  public static final IssueCode UNKNOWN_BACKUP_REFERENCE =
      IssueCode.of("archive.reference.unknown_backup");

  /**
   * WARNING: the pending activation names a season that is no longer the active one, so it is
   * ignored (manifest 1.1, ADR 0011). It can only arise from a writer that did not understand it.
   */
  public static final IssueCode PENDING_ACTIVATION_STALE =
      IssueCode.of("archive.pending_activation.stale");

  /** WARNING: a season origin is not aligned to its dimension's recommended alignment. */
  public static final IssueCode ORIGIN_MISALIGNED = IssueCode.of("season.origin.misaligned");

  /** WARNING: a season has Overworld and Nether origins that are not exactly paired (÷8). */
  public static final IssueCode ORIGIN_NETHER_UNPAIRED =
      IssueCode.of("season.origin.nether_unpaired");

  private ArchiveIssueCodes() {}
}
