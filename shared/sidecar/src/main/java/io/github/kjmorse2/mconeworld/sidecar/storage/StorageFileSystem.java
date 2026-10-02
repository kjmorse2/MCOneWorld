// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Every filesystem operation the sidecar storage layer performs. This is the single seam for fault
 * injection: tests wrap {@link JdkStorageFileSystem} to fail specific calls, to simulate full
 * disks, unsupported atomic moves, or crashes, and to count writes and open handles.
 *
 * <p>Every method treats paths literally and never follows a symbolic link, unless its
 * documentation says otherwise.
 */
public interface StorageFileSystem {

  /** Test hook called at each {@link CommitStage}. Production implementations do nothing. */
  default void checkpoint(CommitStage stage, @Nullable Path subject) throws IOException {}

  /** Returns whether anything, including a dangling symlink, exists at {@code path}. */
  boolean exists(Path path);

  boolean isSymbolicLink(Path path);

  boolean isRegularFile(Path path);

  boolean isDirectory(Path path);

  /** Creates one directory; the parent must exist. */
  void createDirectory(Path dir) throws IOException;

  /** Creates a new file, failing if anything exists at {@code file}, and opens it for writing. */
  WritableFile createNew(Path file) throws IOException;

  /**
   * Opens an existing regular file for appending: writes go to its end. Used only for append-only
   * operation journals.
   */
  WritableFile openAppend(Path file) throws IOException;

  /** Returns the size in bytes of an existing regular file. */
  long size(Path file) throws IOException;

  /** Opens an existing regular file for reading. */
  InputStream openRead(Path file) throws IOException;

  /**
   * Opens an existing regular file for positioned reads. Used by the chunk index, which reads
   * single blocks out of large segment files.
   */
  ReadableFile openRandomAccess(Path file) throws IOException;

  /**
   * Renames {@code source} to {@code target} atomically, replacing an existing target file.
   *
   * @throws java.nio.file.AtomicMoveNotSupportedException if the filesystem cannot rename
   *     atomically. Callers refuse the operation; they never fall back to a copy.
   */
  void moveAtomically(Path source, Path target) throws IOException;

  /** Deletes a file or an empty directory. */
  void delete(Path path) throws IOException;

  /** Lists a directory's entries, sorted by name. */
  List<Path> list(Path dir) throws IOException;

  /**
   * Flushes directory metadata (for example, a rename) to stable storage.
   *
   * @throws UnsupportedOperationException if the platform cannot sync directories. Callers then
   *     report reduced durability instead of failing.
   */
  void syncDirectory(Path dir) throws IOException;

  /** Resolves {@code path} to its real path, following symlinks. Used only for the sidecar root. */
  Path toRealPath(Path path) throws IOException;

  /** A file opened for writing by {@link #createNew(Path)}. */
  interface WritableFile extends Closeable {
    void write(byte[] bytes, int offset, int length) throws IOException;

    /** Forces written content and metadata to stable storage ({@code fsync}). */
    void sync() throws IOException;

    /**
     * Forces written content, and the metadata needed to read it back such as the file length, to
     * stable storage ({@code fdatasync}). Cheaper than {@link #sync()}; used for append-only logs.
     */
    default void syncData() throws IOException {
      sync();
    }
  }

  /**
   * A file opened by {@link #openRandomAccess(Path)}. Positioned reads are safe from several
   * threads at once.
   */
  interface ReadableFile extends Closeable {
    /** The file's current size in bytes. */
    long size() throws IOException;

    /**
     * Reads exactly {@code length} bytes starting at {@code position} into {@code target}.
     *
     * @throws java.io.EOFException if the file ends first
     */
    void readFully(long position, byte[] target, int offset, int length) throws IOException;
  }
}
