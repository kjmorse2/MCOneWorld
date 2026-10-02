// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Durable writes to the {@code chunk-index/} area, under the writer lock. The chunk index (ADR
 * 0007) is the only user. Every method first verifies the lock ({@code LOCK_LOST}). Paths are
 * relative to {@code chunk-index/}.
 *
 * <p>The primitives are deliberately few:
 *
 * <ul>
 *   <li>{@link #createArea()} and {@link #createDirectory}: directories, with the parent synced
 *   <li>{@link #createNew}: a new file that the caller writes and syncs (segments, logs)
 *   <li>{@link #openAppend}: append to a file whose size the caller proves it knows (logs)
 *   <li>{@link #replaceAtomically}: write a temporary file durably, then rename it over the target
 *       (the catalog, the index's commit point)
 *   <li>{@link #delete}: remove one file the caller has proven obsolete (explicit maintenance only)
 * </ul>
 *
 * <p>At most one {@code IndexFileWriter} is open per {@link SidecarWriter}, so two index handles in
 * one process can never interleave writes. {@link #close()} releases that claim; it does not close
 * files the caller opened.
 */
public final class IndexFileWriter implements AutoCloseable {
  private final SidecarWriter writer;
  private final IndexFiles files;
  private final StorageFileSystem fs;
  private boolean closed;

  IndexFileWriter(SidecarWriter writer) {
    this.writer = writer;
    this.files = writer.store().indexFiles();
    this.fs = writer.store().fs();
  }

  /** Read access to the same area. */
  public IndexFiles files() {
    return files;
  }

  /**
   * Verifies that the writer still holds the lock and this claim is open.
   *
   * @throws StorageException {@code LOCK_LOST}
   * @throws IllegalStateException if this writer was closed
   */
  public void verifyLockHeld() throws StorageException {
    if (closed) {
      throw new IllegalStateException("The index file writer is closed");
    }
    writer.lock().verifyHeld();
  }

  /** Creates {@code chunk-index/} and syncs the sidecar root. */
  public void createArea() throws IOException {
    verifyLockHeld();
    Path area = writer.store().root().internal(SidecarRoot.CHUNK_INDEX);
    fs.createDirectory(area);
    syncDir(area.getParent());
  }

  /** Creates one directory (the parent must exist) and syncs its parent. */
  public void createDirectory(SidecarPath path) throws IOException {
    verifyLockHeld();
    Path dir = files.resolve(path);
    fs.createDirectory(dir);
    syncDir(dir.getParent());
  }

  /**
   * Creates a new file, failing if anything exists there. The caller writes it, syncs it, then
   * calls {@link #syncDirectoryOf} so that the new name is durable too.
   */
  public StorageFileSystem.WritableFile createNew(SidecarPath path) throws IOException {
    verifyLockHeld();
    return fs.createNew(files.resolve(path));
  }

  /**
   * Opens a file for appending. It must hold exactly {@code expectedSize} bytes, which proves the
   * caller's view of it is current.
   *
   * @throws StorageException {@code BASE_CHANGED} if the size differs
   */
  public StorageFileSystem.WritableFile openAppend(SidecarPath path, long expectedSize)
      throws IOException {
    verifyLockHeld();
    Path file = files.resolve(path);
    long actual = fs.size(file);
    if (actual != expectedSize) {
      throw new StorageException(
          StorageIssueCodes.BASE_CHANGED,
          "chunk-index/" + path + " holds " + actual + " bytes; expected " + expectedSize);
    }
    return fs.openAppend(file);
  }

  /** Syncs the directory containing {@code path}, where the platform allows it. */
  public void syncDirectoryOf(SidecarPath path) throws IOException {
    syncDir(files.resolve(path).getParent());
  }

  /**
   * Durably writes {@code content} to {@code temp}, then renames it over {@code target} atomically
   * and syncs the directory. {@code temp} must not exist: a leftover is the caller's to explain.
   *
   * @throws StorageException {@code RECOVERY_REQUIRED} if {@code temp} exists, or {@code
   *     ATOMIC_MOVE_UNSUPPORTED}
   */
  public void replaceAtomically(SidecarPath temp, SidecarPath target, byte[] content)
      throws IOException {
    verifyLockHeld();
    Path tempFile = files.resolve(temp);
    Path targetFile = files.resolve(target);
    if (fs.exists(tempFile)) {
      throw new StorageException(
          StorageIssueCodes.RECOVERY_REQUIRED, "chunk-index/" + temp + " already exists");
    }
    try {
      try (StorageFileSystem.WritableFile out = fs.createNew(tempFile)) {
        out.write(content, 0, content.length);
        out.sync();
      }
      fs.moveAtomically(tempFile, targetFile);
    } catch (AtomicMoveNotSupportedException e) {
      deleteQuietly(tempFile, e);
      throw new StorageException(
          StorageIssueCodes.ATOMIC_MOVE_UNSUPPORTED,
          "The filesystem cannot rename atomically; refusing to write",
          e);
    } catch (IOException e) {
      deleteQuietly(tempFile, e);
      throw e;
    }
    syncDir(targetFile.getParent());
  }

  /** Deletes one file. Callers use it only for files they have proven obsolete. */
  public void delete(SidecarPath path) throws IOException {
    verifyLockHeld();
    fs.delete(files.resolve(path));
  }

  /** Releases this writer's claim on the area. Idempotent. */
  @Override
  public void close() {
    if (!closed) {
      closed = true;
      writer.releaseIndexClaim(this);
    }
  }

  private void deleteQuietly(Path file, Exception failure) {
    try {
      if (fs.exists(file)) {
        fs.delete(file);
      }
    } catch (IOException suppressed) {
      failure.addSuppressed(suppressed);
    }
  }

  private void syncDir(@Nullable Path dir) throws IOException {
    if (dir == null) {
      return;
    }
    try {
      fs.syncDirectory(dir);
    } catch (UnsupportedOperationException e) {
      // Reduced durability on this platform (ADR 0004 §4); the data itself is synced.
    }
  }
}
