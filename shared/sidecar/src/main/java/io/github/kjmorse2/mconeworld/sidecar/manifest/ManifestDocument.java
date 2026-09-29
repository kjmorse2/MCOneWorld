// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import io.github.kjmorse2.mconeworld.domain.ArchiveId;
import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.archive.ArchiveState;
import io.github.kjmorse2.mconeworld.domain.archive.BackupRecord;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Everything stored in {@code manifest.json}: the archive identity, the monotonic revision, the
 * domain {@link ArchiveState}, where backups live, referenced data files, archive-history features,
 * and preserved members.
 *
 * <p>Instances are immutable, and "with" methods return copies. Collections are unmodifiable copies
 * in canonical order: files by path, and maps by key. A document can be <em>constructed</em> in an
 * invalid state; {@link #validate()} reports the problems, and {@link ManifestCodec} never encodes
 * an invalid document.
 *
 * @param schemaVersion the version this document was read as, or {@link ManifestCodec#CURRENT} for
 *     a new document. The major version is always 1.
 * @param archiveId stable archive identity
 * @param createdAt when the archive was created
 * @param revision monotonic sidecar revision; never negative. Every committed change must use
 *     {@link #nextRevision()}.
 * @param state seasons and records
 * @param backupPaths where each backup's bytes live, under {@code backups/}
 * @param files referenced data files, sorted by path
 * @param features archive-history feature records
 * @param preserved members carried through unchanged
 */
public record ManifestDocument(
    SchemaVersion schemaVersion,
    ArchiveId archiveId,
    Instant createdAt,
    long revision,
    ArchiveState state,
    SortedMap<BackupId, SidecarPath> backupPaths,
    List<FileReference> files,
    SortedMap<FeatureKey, FeatureRecord> features,
    PreservedMembers preserved) {

  /** Directory under which backup locations must lie. */
  public static final String BACKUPS_DIRECTORY = "backups";

  /** The manifest's own file name, which no reference may point at. */
  public static final String MANIFEST_FILE_NAME = "manifest.json";

  /**
   * @throws InvalidDomainValueException with {@code VALUE_INVALID} if the revision is negative or
   *     the schema major version is not 1
   */
  public ManifestDocument {
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(archiveId, "archiveId");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(preserved, "preserved");
    if (schemaVersion.major() != ManifestCodec.CURRENT.major()) {
      throw new InvalidDomainValueException(
          DomainErrorCode.VALUE_INVALID,
          "ManifestDocument represents schema major " + ManifestCodec.CURRENT.major() + " only");
    }
    if (revision < 0) {
      throw new InvalidDomainValueException(
          DomainErrorCode.VALUE_INVALID, "Revision must not be negative");
    }
    backupPaths = Collections.unmodifiableSortedMap(new TreeMap<>(backupPaths));
    List<FileReference> sorted = new ArrayList<>(files);
    sorted.forEach(f -> Objects.requireNonNull(f, "file"));
    sorted.sort(Comparator.comparing(FileReference::path));
    files = List.copyOf(sorted);
    features = Collections.unmodifiableSortedMap(new TreeMap<>(features));
  }

  /** A new, empty archive at revision 0 and the current schema version. */
  public static ManifestDocument create(ArchiveId archiveId, Instant createdAt) {
    return new ManifestDocument(
        ManifestCodec.CURRENT,
        archiveId,
        createdAt,
        0,
        ArchiveState.empty(),
        new TreeMap<>(),
        List.of(),
        new TreeMap<>(),
        PreservedMembers.none());
  }

  /** Returns a copy with revision + 1. The sidecar revision only moves forward. */
  public ManifestDocument nextRevision() {
    return new ManifestDocument(
        schemaVersion,
        archiveId,
        createdAt,
        Math.addExact(revision, 1),
        state,
        backupPaths,
        files,
        features,
        preserved);
  }

  /** Returns a copy with a different archive state. Preserved members follow surviving records. */
  public ManifestDocument withState(ArchiveState newState) {
    return new ManifestDocument(
        schemaVersion,
        archiveId,
        createdAt,
        revision,
        newState,
        backupPaths,
        files,
        features,
        preserved);
  }

  /** Adds a backup record and its location in one step, so the two cannot diverge. */
  public ManifestDocument withBackup(BackupRecord backup, SidecarPath location) {
    SortedMap<BackupId, SidecarPath> paths = new TreeMap<>(backupPaths);
    paths.put(backup.id(), location);
    return new ManifestDocument(
        schemaVersion,
        archiveId,
        createdAt,
        revision,
        state.withBackup(backup),
        paths,
        files,
        features,
        preserved);
  }

  /** Adds or replaces (by path) a referenced file. */
  public ManifestDocument withFile(FileReference file) {
    List<FileReference> next =
        new ArrayList<>(files.stream().filter(f -> !f.path().equals(file.path())).toList());
    next.add(file);
    return new ManifestDocument(
        schemaVersion,
        archiveId,
        createdAt,
        revision,
        state,
        backupPaths,
        next,
        features,
        preserved);
  }

  /** Adds or replaces a feature record. */
  public ManifestDocument withFeature(FeatureKey key, FeatureRecord record) {
    SortedMap<FeatureKey, FeatureRecord> next = new TreeMap<>(features);
    next.put(key, record);
    return new ManifestDocument(
        schemaVersion, archiveId, createdAt, revision, state, backupPaths, files, next, preserved);
  }

  /**
   * Validates the archive state ({@link ArchiveState#validate()}) plus the manifest-level rules:
   * every backup has exactly one location under {@code backups/}; paths are unique across files and
   * backups; and nothing points at {@code manifest.json}.
   */
  public ValidationResult validate() {
    List<ValidationIssue> issues = new ArrayList<>(state.validate().issues());
    IssueLocation root = IssueLocation.root();
    Set<BackupId> recorded = new HashSet<>();
    for (BackupRecord backup : state.backups()) {
      recorded.add(backup.id());
      if (!backupPaths.containsKey(backup.id())) {
        issues.add(
            ValidationIssue.error(
                ManifestIssueCodes.BACKUP_PATH_MISSING,
                root.field("backups").key(backup.id().toString()),
                "Backup has no stored location"));
      }
    }
    Set<SidecarPath> paths = new HashSet<>();
    for (Map.Entry<BackupId, SidecarPath> entry : backupPaths.entrySet()) {
      IssueLocation at = root.field("backups").key(entry.getKey().toString()).field("path");
      if (!recorded.contains(entry.getKey())) {
        issues.add(
            ValidationIssue.error(
                ManifestIssueCodes.BACKUP_PATH_UNKNOWN,
                at,
                "Location for a backup that has no record"));
      }
      if (!entry.getValue().isUnder(BACKUPS_DIRECTORY)) {
        issues.add(
            ValidationIssue.error(
                ManifestIssueCodes.INVALID_VALUE,
                at,
                "Backup locations must lie under " + BACKUPS_DIRECTORY + "/"));
      }
      checkPath(entry.getValue(), at, paths, issues);
    }
    for (FileReference file : files) {
      checkPath(file.path(), root.field("files").key(file.path().toString()), paths, issues);
    }
    return ValidationResult.of(issues);
  }

  private static void checkPath(
      SidecarPath path, IssueLocation at, Set<SidecarPath> seen, List<ValidationIssue> issues) {
    if (path.value().equals(MANIFEST_FILE_NAME)) {
      issues.add(
          ValidationIssue.error(
              ManifestIssueCodes.INVALID_VALUE,
              at,
              "References may not point at the manifest itself"));
    }
    if (!seen.add(path)) {
      issues.add(
          ValidationIssue.error(
              ManifestIssueCodes.DUPLICATE_PATH,
              at,
              "Path " + path + " is referenced more than once"));
    }
  }
}
