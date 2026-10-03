// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.backup;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/** Stable codes of the backup workflow. Never rename or repurpose one. */
public final class BackupIssueCodes {
  /** The session does not allow a backup (see the findings for every reason). */
  public static final IssueCode NOT_ALLOWED = IssueCode.of("tool.backup.not_allowed");

  /** The sidecar's filesystem lacks the free space the backup needs. */
  public static final IssueCode INSUFFICIENT_SPACE = IssueCode.of("tool.backup.insufficient_space");

  /** A world file cannot be backed up as it is: a symbolic link, or a name a backup cannot hold. */
  public static final IssueCode UNSUPPORTED_FILE = IssueCode.of("tool.backup.unsupported_file");

  /** The world or the sidecar changed since planning, or during the copy. */
  public static final IssueCode CHANGED = IssueCode.of("tool.backup.changed");

  /** The copy does not verify, or a post-check failed. */
  public static final IssueCode VALIDATION_FAILED = IssueCode.of("tool.backup.validation_failed");

  /** The journal is not a backup, or not in a state this Tool can continue. */
  public static final IssueCode NOT_RESUMABLE = IssueCode.of("tool.backup.not_resumable");

  private BackupIssueCodes() {}
}
