// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Read access to the backups area, {@code backups/<backupId>/} (ADR 0012). Safe without the writer
 * lock. Symbolic links are never followed: one anywhere in a backup is an error.
 */
public final class BackupFiles {
  /** The area's directory name, which is also where manifest backup paths must lie. */
  public static final String AREA = "backups";

  /** Each backup's own manifest, relative to its directory. */
  public static final String MANIFEST = "backup.json";

  private final SidecarStore store;

  BackupFiles(SidecarStore store) {
    this.store = store;
  }

  /** The sidecar path of a backup's {@value #MANIFEST}: what the sidecar manifest references. */
  public static SidecarPath manifestPath(BackupId id) {
    return SidecarPath.of(AREA + "/" + id + "/" + MANIFEST);
  }

  /** Whether a directory exists for {@code id} (complete or partial). */
  public boolean exists(BackupId id) throws StorageException {
    return store.fs().isDirectory(directory(id));
  }

  /**
   * Whether {@code id}'s {@value #MANIFEST} exists. A directory without one is a partial backup.
   */
  public boolean hasManifest(BackupId id) throws StorageException {
    return store.fs().isRegularFile(store.root().resolve(manifestPath(id), store.fs()));
  }

  /** Reads {@code id}'s {@value #MANIFEST}, refusing more than {@code maxBytes}. */
  public byte[] readManifest(BackupId id, long maxBytes) throws IOException {
    try (InputStream in = store.fs().openRead(store.root().resolve(manifestPath(id), store.fs()))) {
      return Checksums.readBounded(in, maxBytes);
    }
  }

  /** Opens one payload file of a backup for reading. */
  public InputStream open(BackupId id, BackupEntryPath entry) throws IOException {
    Path file = resolve(id, entry);
    if (!store.fs().isRegularFile(file)) {
      throw new StorageException(StorageIssueCodes.IO_FAILURE, "Not a regular file: " + entry);
    }
    return store.fs().openRead(file);
  }

  /** Whether one payload file exists as a regular file. */
  public boolean isFile(BackupId id, BackupEntryPath entry) throws StorageException {
    return store.fs().isRegularFile(resolve(id, entry));
  }

  /** The size of one payload file. */
  public long size(BackupId id, BackupEntryPath entry) throws IOException {
    return store.fs().size(resolve(id, entry));
  }

  /**
   * Hands every payload file of {@code id} to {@code sink}: every regular file below its {@code
   * world/} and {@code sidecar/} directories, in no particular order. A file whose name is not a
   * valid entry path, or anything else unexpected at the top level, goes to {@code unexpected} as
   * its path relative to the backup directory.
   *
   * @throws StorageException {@code SYMLINK_REFUSED} if a symbolic link is found
   */
  public void walk(BackupId id, Consumer<BackupEntryPath> sink, Consumer<String> unexpected)
      throws IOException {
    Path dir = directory(id);
    for (Path child : store.fs().list(dir)) {
      String name = Objects.requireNonNull(child.getFileName()).toString();
      if (store.fs().isSymbolicLink(child)) {
        throw symlink(name);
      }
      if (name.equals(MANIFEST) && store.fs().isRegularFile(child)) {
        continue;
      }
      if ((name.equals(BackupEntryPath.WORLD) || name.equals(BackupEntryPath.SIDECAR))
          && store.fs().isDirectory(child)) {
        walkArea(child, name, sink, unexpected);
      } else {
        unexpected.accept(name);
      }
    }
  }

  private void walkArea(
      Path dir, String prefix, Consumer<BackupEntryPath> sink, Consumer<String> unexpected)
      throws IOException {
    List<Path> children = new ArrayList<>(store.fs().list(dir));
    for (Path child : children) {
      String path = prefix + "/" + Objects.requireNonNull(child.getFileName());
      if (store.fs().isSymbolicLink(child)) {
        throw symlink(path);
      }
      if (store.fs().isDirectory(child)) {
        walkArea(child, path, sink, unexpected);
      } else if (store.fs().isRegularFile(child) && BackupEntryPath.isValid(path)) {
        sink.accept(BackupEntryPath.of(path));
      } else {
        unexpected.accept(path);
      }
    }
  }

  Path directory(BackupId id) throws StorageException {
    return store.root().resolve(SidecarPath.of(AREA + "/" + id), store.fs());
  }

  /** Resolves a payload path beneath the backup's directory, refusing symbolic links. */
  Path resolve(BackupId id, BackupEntryPath entry) throws StorageException {
    Path base = directory(id);
    Path current = base;
    for (String segment : entry.segments()) {
      current = current.resolve(segment);
      if (store.fs().isSymbolicLink(current)) {
        throw symlink(entry.value());
      }
    }
    if (!current.normalize().startsWith(base)) {
      throw new StorageException(
          StorageIssueCodes.PATH_ESCAPE, "Path escapes the backup directory: " + entry);
    }
    return current;
  }

  private static StorageException symlink(String path) {
    return new StorageException(
        StorageIssueCodes.SYMLINK_REFUSED, "Symbolic links are not allowed in a backup: " + path);
  }
}
