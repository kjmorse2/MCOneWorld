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
 * Read-only access to the sidecar's {@code operations/} area, where operation plans and journals
 * live (see {@code docs/formats/operation-journal-v1.md}). Paths are {@link SidecarPath}s relative
 * to {@code operations/}, for example {@code <operation-id>/journal.jsonl}. The symlink policy of
 * {@link SidecarRoot} applies. Reading never writes anything.
 *
 * <p>Obtain it from {@link SidecarStore#operationFiles()}. Writing requires the writer lock: {@link
 * SidecarWriter#operationFiles()}.
 */
public final class OperationFiles {
  private final SidecarStore store;

  OperationFiles(SidecarStore store) {
    this.store = store;
  }

  /** Returns whether a file or directory exists at {@code path}. */
  public boolean exists(SidecarPath path) throws StorageException {
    return store.fs().exists(resolve(path));
  }

  /**
   * Returns the names of the entries of {@code directory} (or of {@code operations/} itself if
   * {@code directory} is {@code null}), sorted. A missing directory has no entries.
   */
  public List<String> list(@Nullable SidecarPath directory) throws IOException {
    Path dir =
        directory == null ? store.root().internal(SidecarRoot.OPERATIONS) : resolve(directory);
    if (directory == null && store.fs().isSymbolicLink(dir)) {
      throw new StorageException(
          StorageIssueCodes.SYMLINK_REFUSED, "operations/ must not be a symbolic link");
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
      throw new NoSuchFileException(SidecarRoot.OPERATIONS + "/" + path);
    }
    try (InputStream in = store.fs().openRead(file)) {
      return Checksums.readBounded(in, maxBytes);
    }
  }

  /** Returns the size of an existing regular file, in bytes. */
  public long size(SidecarPath path) throws IOException {
    return store.fs().size(resolve(path));
  }

  /** Resolves a path relative to {@code operations/}, enforcing the symlink policy. */
  Path resolve(SidecarPath path) throws StorageException {
    return store.root().resolve(SidecarPath.of(SidecarRoot.OPERATIONS + "/" + path), store.fs());
  }
}
