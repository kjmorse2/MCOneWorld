// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.backup;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupEntryPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupFiles;
import io.github.kjmorse2.mconeworld.sidecar.storage.Checksums;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Re-reads every file of a backup and compares it with its manifest (ADR 0012). Read-only and safe
 * without the writer lock. Interrupting the thread stops it between files.
 */
public final class BackupVerifier {
  private final BackupManifestCodec codec = new BackupManifestCodec();

  /**
   * Verifies a recorded backup: reads its {@code backup.json}, checks it against {@code
   * expectedManifest} (the checksum the sidecar manifest records) when given, then verifies the
   * payload.
   *
   * @throws InterruptedIOException if the thread is interrupted
   * @throws IOException if a file cannot be read
   */
  public BackupVerification verify(
      BackupFiles files, BackupId id, @Nullable Checksum expectedManifest) throws IOException {
    IssueLocation at = IssueLocation.root().key(BackupFiles.manifestPath(id).value());
    if (!files.hasManifest(id)) {
      return failed(
          id,
          ValidationIssue.error(
                  BackupIssueCodes.PARTIAL, at, "The backup has no " + BackupFiles.MANIFEST)
              .withRemediation(
                  "It was interrupted before it was recorded. Delete it explicitly, or ignore it."));
    }
    byte[] bytes = files.readManifest(id, BackupManifestCodec.MAX_BYTES);
    if (expectedManifest != null) {
      Checksum actual =
          Checksums.sha256(new java.io.ByteArrayInputStream(bytes), bytes.length).checksum();
      if (!actual.equals(expectedManifest)) {
        return failed(
            id,
            ValidationIssue.error(
                BackupIssueCodes.MANIFEST_CHANGED,
                at,
                BackupFiles.MANIFEST
                    + " has checksum "
                    + actual
                    + ", not the recorded "
                    + expectedManifest));
      }
    }
    BackupManifest manifest;
    try {
      manifest = codec.decode(bytes);
    } catch (BackupFormatException e) {
      return failed(id, ValidationIssue.error(e.code(), at, String.valueOf(e.getMessage())));
    }
    if (!manifest.id().equals(id)) {
      return failed(
          id,
          ValidationIssue.error(
              BackupIssueCodes.INVALID,
              at,
              "The manifest names backup " + manifest.id() + ", not " + id));
    }
    return verifyPayload(files, manifest);
  }

  /**
   * Verifies the payload of {@code manifest}'s backup against the manifest itself, before or after
   * {@code backup.json} is written: every listed file present with its size and SHA-256, and no
   * other file under {@code world/} or {@code sidecar/}.
   *
   * @throws InterruptedIOException if the thread is interrupted
   * @throws IOException if a file cannot be read
   */
  public BackupVerification verifyPayload(BackupFiles files, BackupManifest manifest)
      throws IOException {
    BackupId id = manifest.id();
    List<ValidationIssue> issues = new ArrayList<>();
    Set<BackupEntryPath> listed = new HashSet<>();
    IssueLocation entries = IssueLocation.root().field("entries");
    for (BackupEntry entry : manifest.entries()) {
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("Backup verification was interrupted");
      }
      listed.add(entry.path());
      IssueLocation at = entries.key(entry.path().value());
      if (!files.isFile(id, entry.path())) {
        issues.add(
            ValidationIssue.error(BackupIssueCodes.ENTRY_MISSING, at, "Missing: " + entry.path()));
        continue;
      }
      Checksums.Measured measured;
      try (InputStream in = files.open(id, entry.path())) {
        measured = Checksums.sha256(in, Math.max(entry.sizeBytes(), files.size(id, entry.path())));
      }
      if (measured.sizeBytes() != entry.sizeBytes()
          || !measured.checksum().equals(entry.checksum())) {
        issues.add(
            ValidationIssue.error(
                BackupIssueCodes.ENTRY_CHANGED,
                at,
                "Changed: "
                    + entry.path()
                    + " has "
                    + measured.sizeBytes()
                    + " bytes and "
                    + measured.checksum()));
      }
    }
    files.walk(
        id,
        found -> {
          if (!listed.contains(found)) {
            issues.add(
                ValidationIssue.error(
                    BackupIssueCodes.ENTRY_UNEXPECTED,
                    entries.key(found.value()),
                    "Not listed: " + found));
          }
        },
        other ->
            issues.add(
                ValidationIssue.error(
                    BackupIssueCodes.ENTRY_UNEXPECTED,
                    IssueLocation.root().key(other),
                    "Not part of a backup: " + other)));
    return new BackupVerification(id, manifest, ValidationResult.of(issues));
  }

  private static BackupVerification failed(BackupId id, ValidationIssue issue) {
    return new BackupVerification(id, null, ValidationResult.of(List.of(issue)));
  }
}
