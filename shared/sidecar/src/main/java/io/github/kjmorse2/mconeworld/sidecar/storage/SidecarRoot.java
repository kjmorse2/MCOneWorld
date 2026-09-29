// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * An explicit, caller-supplied sidecar root directory. There is no global or discovered sidecar
 * path anywhere in shared code.
 *
 * <p><b>Symlink policy</b> (see {@code docs/formats/sidecar-storage-v1.md}):
 *
 * <ul>
 *   <li>The root path itself may be reached through symlinks. It is resolved <em>once</em>, to its
 *       real path, when the root is opened.
 *   <li>Below the root, no existing path component may be a symbolic link. {@link
 *       #resolve(SidecarPath, StorageFileSystem)} refuses such paths, because a link could redirect
 *       a write outside the sidecar or into a world directory.
 * </ul>
 *
 * <p>The checks happen at resolution time. A link created afterwards by another process is out of
 * scope; the writer lock excludes cooperating writers, but not arbitrary processes.
 */
public final class SidecarRoot {
  /** The manifest file name. */
  public static final String MANIFEST = "manifest.json";

  /** The previous committed manifest, kept as the last-known-good fallback. */
  public static final String PREVIOUS_MANIFEST = "manifest.previous.json";

  /**
   * The writer lock target: a permanent, empty file that the OS lock is taken on. It is never
   * deleted.
   */
  public static final String LOCK = "writer.lock";

  /**
   * The current lock owner's metadata. It exists only while a writer holds (or crashed holding) the
   * lock.
   */
  public static final String LOCK_OWNER = "writer.owner.json";

  /** Directory holding in-progress transactions. */
  public static final String STAGING = ".staging";

  private static final Set<String> RESERVED_TOP_LEVEL =
      Set.of(MANIFEST, PREVIOUS_MANIFEST, LOCK, LOCK_OWNER, LOCK_OWNER + ".tmp", STAGING);

  private final Path real;

  private SidecarRoot(Path real) {
    this.real = real;
  }

  /**
   * Opens an existing sidecar root directory. The directory is not modified.
   *
   * @throws StorageException {@code ROOT_INVALID} if it does not exist or is not a directory
   */
  public static SidecarRoot open(Path root, StorageFileSystem fs) throws StorageException {
    Objects.requireNonNull(root, "root");
    Path real;
    try {
      real = fs.toRealPath(root.toAbsolutePath());
    } catch (IOException e) {
      throw new StorageException(
          StorageIssueCodes.ROOT_INVALID, "Sidecar root cannot be resolved: " + root, e);
    }
    if (!fs.isDirectory(real)) {
      throw new StorageException(
          StorageIssueCodes.ROOT_INVALID, "Sidecar root is not a directory: " + root);
    }
    return new SidecarRoot(real);
  }

  /** Opens a root with the production filesystem. */
  public static SidecarRoot open(Path root) throws StorageException {
    return open(root, JdkStorageFileSystem.INSTANCE);
  }

  /** The real, absolute root directory. This is machine-specific: never persist it. */
  public Path path() {
    return real;
  }

  /** Returns whether {@code path} names sidecar bookkeeping that manifests must not reference. */
  public static boolean isReserved(SidecarPath path) {
    return RESERVED_TOP_LEVEL.contains(path.segments().get(0));
  }

  /**
   * Resolves a sidecar-relative path beneath the root, enforcing the symlink policy. The target
   * need not exist.
   *
   * @throws StorageException {@code SYMLINK_REFUSED} or {@code PATH_ESCAPE}
   */
  public Path resolve(SidecarPath path, StorageFileSystem fs) throws StorageException {
    Path current = real;
    for (String segment : path.segments()) {
      current = current.resolve(segment);
      if (fs.isSymbolicLink(current)) {
        throw new StorageException(
            StorageIssueCodes.SYMLINK_REFUSED,
            "Symbolic links are not allowed inside the sidecar: " + path);
      }
    }
    // SidecarPath syntax already excludes traversal; this is defence in depth.
    if (!current.normalize().startsWith(real)) {
      throw new StorageException(
          StorageIssueCodes.PATH_ESCAPE, "Path escapes the sidecar root: " + path);
    }
    return current;
  }

  /** Resolves one of the reserved top-level names (for storage internals). */
  Path internal(String name) {
    return real.resolve(name);
  }

  @Override
  public String toString() {
    return "SidecarRoot[" + real + "]";
  }
}
