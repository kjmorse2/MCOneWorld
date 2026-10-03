// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.backup;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/** Stable codes for backup manifests and verification. Never rename or repurpose one. */
public final class BackupIssueCodes {
  /** The input is not a backup manifest ({@code format} is not {@code mconeworld-backup}). */
  public static final IssueCode NOT_A_BACKUP = IssueCode.of("sidecar.backup.not_a_backup");

  /** The manifest's major version is not 1. */
  public static final IssueCode UNSUPPORTED_VERSION =
      IssueCode.of("sidecar.backup.unsupported_version");

  /** A member is missing, unknown, of the wrong type, or holds an invalid value. */
  public static final IssueCode INVALID = IssueCode.of("sidecar.backup.invalid");

  /** The backup directory has no {@code backup.json}: an interrupted, never recorded backup. */
  public static final IssueCode PARTIAL = IssueCode.of("sidecar.backup.partial");

  /** {@code backup.json} does not match the checksum the sidecar manifest records for it. */
  public static final IssueCode MANIFEST_CHANGED = IssueCode.of("sidecar.backup.manifest_changed");

  /** A listed file is missing. */
  public static final IssueCode ENTRY_MISSING = IssueCode.of("sidecar.backup.entry_missing");

  /** A listed file's size or SHA-256 differs from the manifest. */
  public static final IssueCode ENTRY_CHANGED = IssueCode.of("sidecar.backup.entry_changed");

  /** A file in the backup is not listed in its manifest. */
  public static final IssueCode ENTRY_UNEXPECTED = IssueCode.of("sidecar.backup.entry_unexpected");

  private BackupIssueCodes() {}
}
