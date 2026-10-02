// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Read-only access to the sidecar's {@code chunk-index/} area (ADR 0007). Paths are {@link
 * SidecarPath}s relative to {@code chunk-index/}, for example {@code
 * segments/seg-0000000000000001.dat}. The symlink policy of {@link SidecarRoot} applies to every
 * component, including {@code chunk-index} itself. Nothing here writes.
 *
 * <p>Obtain it from {@link SidecarStore#indexFiles()}. Writing requires the writer lock: {@link
 * SidecarWriter#openIndexWriter()}.
 */
public final class IndexFiles {
  private final SidecarStore store;

  IndexFiles(SidecarStore store) {
    this.store = store;
  }

  /** Returns whether the {@code chunk-index/} directory exists. */
  public boolean areaExists() throws StorageException {
    Path area = store.root().internal(SidecarRoot.CHUNK_INDEX);
    if (store.fs().isSymbolicLink(area)) {
      throw new StorageException(
          StorageIssueCodes.SYMLINK_REFUSED, "chunk-index/ must not be a symbolic link");
    }
    return store.fs().isDirectory(area);
  }

  /** Returns whether a file or directory exists at {@code path}. */
  public boolean exists(SidecarPath path) throws StorageException {
    return store.fs().exists(resolve(path));
  }

  /**
   * Returns the names of the entries of {@code directory} (or of {@code chunk-index/} itself if
   * {@code null}), sorted. A missing directory has no entries.
   */
  public List<String> list(@Nullable SidecarPath directory) throws IOException {
    Path dir =
        directory == null ? store.root().internal(SidecarRoot.CHUNK_INDEX) : resolve(directory);
    if (directory == null && !areaExists()) {
      return List.of();
    }
    if (!store.fs().isDirectory(dir)) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    for (Path entry : store.fs().list(dir)) {
      names.add(entry.getFileName().toString());
    }
    return List.copyOf(names);
  }

  /**
   * Reads a whole file, refusing more than {@code maxBytes}.
   *
   * @throws NoSuchFileException if it does not exist
   * @throws StorageException {@code TOO_LARGE} if it holds more than {@code maxBytes}
   */
  public byte[] read(SidecarPath path, long maxBytes) throws IOException {
    Path file = resolve(path);
    if (!store.fs().isRegularFile(file)) {
      throw new NoSuchFileException(SidecarRoot.CHUNK_INDEX + "/" + path);
    }
    try (InputStream in = store.fs().openRead(file)) {
      return Checksums.readBounded(in, maxBytes);
    }
  }

  /** Returns the size of an existing regular file, in bytes. */
  public long size(SidecarPath path) throws IOException {
    return store.fs().size(resolve(path));
  }

  /**
   * Opens an existing regular file for positioned reads. The caller owns the handle.
   *
   * @throws NoSuchFileException if it does not exist
   */
  public StorageFileSystem.ReadableFile openRandomAccess(SidecarPath path) throws IOException {
    Path file = resolve(path);
    if (!store.fs().isRegularFile(file)) {
      throw new NoSuchFileException(SidecarRoot.CHUNK_INDEX + "/" + path);
    }
    return store.fs().openRandomAccess(file);
  }

  /** Resolves a path relative to {@code chunk-index/}, enforcing the symlink policy. */
  Path resolve(SidecarPath path) throws StorageException {
    return store.root().resolve(SidecarPath.of(SidecarRoot.CHUNK_INDEX + "/" + path), store.fs());
  }
}
