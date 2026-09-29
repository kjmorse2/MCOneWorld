// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/**
 * Stable codes carried by {@link StorageException}. See {@code docs/formats/sidecar-storage-v1.md}.
 */
public final class StorageIssueCodes {
  /** The sidecar root is missing, is not a directory, or cannot be resolved. */
  public static final IssueCode ROOT_INVALID = IssueCode.of("sidecar.storage.root_invalid");

  /** A path component inside the sidecar is a symbolic link (refused by policy). */
  public static final IssueCode SYMLINK_REFUSED = IssueCode.of("sidecar.storage.symlink_refused");

  /** A path resolves outside the sidecar root. */
  public static final IssueCode PATH_ESCAPE = IssueCode.of("sidecar.storage.path_escape");

  /** A path is reserved for sidecar bookkeeping (manifest, lock, staging). */
  public static final IssueCode RESERVED_PATH = IssueCode.of("sidecar.storage.reserved_path");

  /** Input is larger than the caller's byte limit. */
  public static final IssueCode TOO_LARGE = IssueCode.of("sidecar.storage.too_large");

  /** Content does not match its recorded checksum or size. */
  public static final IssueCode CHECKSUM_MISMATCH =
      IssueCode.of("sidecar.storage.checksum_mismatch");

  /**
   * The filesystem cannot rename atomically; the write was refused rather than risk a torn file.
   */
  public static final IssueCode ATOMIC_MOVE_UNSUPPORTED =
      IssueCode.of("sidecar.storage.atomic_move_unsupported");

  /** Another live writer holds the sidecar lock. */
  public static final IssueCode LOCK_CONTENDED = IssueCode.of("sidecar.storage.lock_contended");

  /** A lock file exists whose owner is provably gone; the policy refused to take it over. */
  public static final IssueCode LOCK_STALE = IssueCode.of("sidecar.storage.lock_stale");

  /** This writer no longer holds the lock (it was removed or replaced externally). */
  public static final IssueCode LOCK_LOST = IssueCode.of("sidecar.storage.lock_lost");

  /** The manifest changed since the transaction's expected base revision. */
  public static final IssueCode BASE_CHANGED = IssueCode.of("sidecar.storage.base_changed");

  /** A committed data file already exists at the target path with different content. */
  public static final IssueCode DATA_FILE_CONFLICT =
      IssueCode.of("sidecar.storage.data_file_conflict");

  /** A newly referenced file could not be verified against its checksum and size. */
  public static final IssueCode REFERENCE_UNVERIFIED =
      IssueCode.of("sidecar.storage.reference_unverified");

  /** The transaction was used incorrectly (wrong revision, already finished, …). */
  public static final IssueCode TRANSACTION_INVALID =
      IssueCode.of("sidecar.storage.transaction_invalid");

  /** The new manifest failed to encode or validate. */
  public static final IssueCode MANIFEST_INVALID = IssueCode.of("sidecar.storage.manifest_invalid");

  /** Unfinished transactions exist; run recovery before starting a new one. */
  public static final IssueCode RECOVERY_REQUIRED =
      IssueCode.of("sidecar.storage.recovery_required");

  /**
   * Recovery: a transaction committed, but its staging directory was not removed. Safe to clean.
   */
  public static final IssueCode RECOVERY_COMMITTED_CLEANUP =
      IssueCode.of("sidecar.recovery.committed_cleanup_pending");

  /** Recovery: a transaction never reached its commit point. Safe to discard, with its orphans. */
  public static final IssueCode RECOVERY_ABORTED_CLEANUP =
      IssueCode.of("sidecar.recovery.aborted_cleanup_pending");

  /**
   * Recovery: the manifest matches neither the transaction's base nor its target. Manual review
   * needed.
   */
  public static final IssueCode RECOVERY_OUTCOME_UNKNOWN =
      IssueCode.of("sidecar.recovery.outcome_unknown");

  /**
   * Recovery: a staging directory without a readable transaction record. Never removed
   * automatically.
   */
  public static final IssueCode RECOVERY_UNEXPLAINED =
      IssueCode.of("sidecar.recovery.unexplained_staging");

  /** Recovery: manifest.json is missing or unreadable (manifest.previous.json may help). */
  public static final IssueCode RECOVERY_MANIFEST_UNREADABLE =
      IssueCode.of("sidecar.recovery.manifest_unreadable");

  /** Any other I/O failure; the cause carries details. */
  public static final IssueCode IO_FAILURE = IssueCode.of("sidecar.storage.io_failure");

  private StorageIssueCodes() {}
}
