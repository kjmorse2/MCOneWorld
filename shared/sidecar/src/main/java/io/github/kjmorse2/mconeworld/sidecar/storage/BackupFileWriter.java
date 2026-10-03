// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Writes the payload of one new backup, {@code backups/<id>/world/…} and {@code sidecar/…}, under
 * the writer lock (ADR 0012). Obtained from {@link SidecarWriter#openBackupWriter}.
 *
 * <p>It never writes {@value BackupFiles#MANIFEST}: that file is staged through a {@link
 * SidecarTransaction} together with the {@code BackupRecord}, and that commit is the backup's
 * commit point. Until then the directory is a partial backup that nothing references; it is never
 * removed automatically ({@link #deletePartial} is explicit).
 *
 * <p>Every file is created new (never overwritten). The caller writes and syncs each file; {@link
 * #syncDirectories()} then makes every new name durable before the commit.
 */
public final class BackupFileWriter implements AutoCloseable {
  private final SidecarWriter writer;
  private final BackupFiles files;
  private final StorageFileSystem fs;
  private final BackupId id;
  private final Set<Path> createdDirectories = new LinkedHashSet<>();
  private boolean closed;

  BackupFileWriter(SidecarWriter writer, BackupId id) {
    this.writer = writer;
    this.files = writer.store().backupFiles();
    this.fs = writer.store().fs();
    this.id = Objects.requireNonNull(id, "id");
  }

  public BackupId id() {
    return id;
  }

  /**
   * Creates {@code backups/} if needed and the backup's own directory, which must not exist.
   *
   * @throws StorageException {@code DATA_FILE_CONFLICT} if the backup directory exists
   */
  public void createDirectory() throws IOException {
    verifyLockHeld();
    Path area = writer.store().root().resolve(SidecarPath.of(BackupFiles.AREA), fs);
    if (!fs.isDirectory(area)) {
      fs.createDirectory(area);
      createdDirectories.add(area);
    }
    Path dir = files.directory(id);
    if (fs.exists(dir)) {
      throw new StorageException(
          StorageIssueCodes.DATA_FILE_CONFLICT, "Backup directory " + id + " already exists");
    }
    fs.createDirectory(dir);
    createdDirectories.add(dir);
  }

  /**
   * Creates one new payload file, and any missing parent directories. The caller writes it, syncs
   * it, and closes it.
   *
   * @throws IOException if the file exists or cannot be created
   */
  public StorageFileSystem.WritableFile createNew(BackupEntryPath entry) throws IOException {
    verifyLockHeld();
    Path file = files.resolve(id, entry);
    Path base = files.directory(id);
    Path parent = Objects.requireNonNull(file.getParent());
    ensureDirectories(base, parent);
    return fs.createNew(file);
  }

  /** Syncs every directory this writer created, so every new name is durable. */
  public void syncDirectories() throws IOException {
    verifyLockHeld();
    for (Path dir : createdDirectories) {
      Path parent = dir.getParent();
      if (parent != null) {
        syncDir(parent);
      }
      syncDir(dir);
    }
  }

  /**
   * Deletes this backup's directory and everything in it. Only for a partial backup: refused if its
   * {@value BackupFiles#MANIFEST} exists. Explicit, never automatic.
   *
   * @throws StorageException {@code DATA_FILE_CONFLICT} if the backup has a manifest
   */
  public void deletePartial() throws IOException {
    verifyLockHeld();
    if (files.hasManifest(id)) {
      throw new StorageException(
          StorageIssueCodes.DATA_FILE_CONFLICT,
          "Backup " + id + " has a manifest; a recorded backup is never deleted this way");
    }
    Path dir = files.directory(id);
    if (fs.isDirectory(dir)) {
      deleteTree(dir);
      Path parent = dir.getParent();
      if (parent != null) {
        syncDir(parent);
      }
    }
  }

  /**
   * Verifies that the writer still holds the lock and this claim is open.
   *
   * @throws StorageException {@code LOCK_LOST}
   */
  public void verifyLockHeld() throws StorageException {
    if (closed) {
      throw new IllegalStateException("The backup file writer is closed");
    }
    writer.verifyLockHeld();
  }

  /** Releases this writer's claim. Idempotent. Files the caller opened are not closed. */
  @Override
  public void close() {
    if (!closed) {
      closed = true;
      writer.releaseBackupClaim(this);
    }
  }

  private void ensureDirectories(Path base, Path dir) throws IOException {
    if (dir.equals(base) || fs.isDirectory(dir)) {
      if (fs.isSymbolicLink(dir)) {
        throw new StorageException(StorageIssueCodes.SYMLINK_REFUSED, "Symbolic link in a backup");
      }
      return;
    }
    Path parent = Objects.requireNonNull(dir.getParent());
    ensureDirectories(base, parent);
    fs.createDirectory(dir);
    createdDirectories.add(dir);
  }

  private void deleteTree(Path dir) throws IOException {
    for (Path child : fs.list(dir)) {
      if (fs.isSymbolicLink(child)) {
        fs.delete(child); // remove the link itself; never follow it
      } else if (fs.isDirectory(child)) {
        deleteTree(child);
      } else {
        fs.delete(child);
      }
    }
    fs.delete(dir);
  }

  private void syncDir(Path dir) throws IOException {
    try {
      fs.syncDirectory(dir);
    } catch (UnsupportedOperationException e) {
      // Reduced durability on this platform (ADR 0004 §4); the data itself is synced.
    }
  }
}
