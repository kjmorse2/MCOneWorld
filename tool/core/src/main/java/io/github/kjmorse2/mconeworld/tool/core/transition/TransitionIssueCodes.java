// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.transition;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/** Stable codes of the season transition. Never rename or repurpose one. */
public final class TransitionIssueCodes {
  /** The session does not allow a new season (see the findings for every reason). */
  public static final IssueCode NOT_ALLOWED = IssueCode.of("tool.transition.not_allowed");

  /** The chunk index differs from the world: reconcile first. */
  public static final IssueCode RECONCILE_NEEDED = IssueCode.of("tool.transition.reconcile_needed");

  /** No recorded full backup holds the world as it is now: make one first. */
  public static final IssueCode BACKUP_NEEDED = IssueCode.of("tool.transition.backup_needed");

  /** The requested origins or spawn break a planning rule. */
  public static final IssueCode ORIGIN_INVALID = IssueCode.of("tool.transition.origin_invalid");

  /** The End directory is not where the verified layout puts it, or holds unsupported files. */
  public static final IssueCode END_UNSUPPORTED = IssueCode.of("tool.transition.end_unsupported");

  /** Protected End chunks would be archived; that must be acknowledged explicitly. */
  public static final IssueCode PROTECTED_END = IssueCode.of("tool.transition.protected_end");

  /** Not enough free space on the sidecar's disk for the End archive. */
  public static final IssueCode INSUFFICIENT_SPACE =
      IssueCode.of("tool.transition.insufficient_space");

  /** The world or the sidecar changed since planning. Terminal: the End is restored. */
  public static final IssueCode CHANGED = IssueCode.of("tool.transition.changed");

  /** The End archive does not verify. Terminal: the End is restored. */
  public static final IssueCode ARCHIVE_UNVERIFIED =
      IssueCode.of("tool.transition.archive_unverified");

  /** A post-apply check failed; the operation is marked failed. */
  public static final IssueCode VALIDATION_FAILED =
      IssueCode.of("tool.transition.validation_failed");

  /** Restoring the End found a file that differs from the archive; nothing was overwritten. */
  public static final IssueCode ROLLBACK_CONFLICT =
      IssueCode.of("tool.transition.rollback_conflict");

  /** The journal is not a season transition, or not in a state this Tool can continue. */
  public static final IssueCode NOT_RESUMABLE = IssueCode.of("tool.transition.not_resumable");

  private TransitionIssueCodes() {}
}
