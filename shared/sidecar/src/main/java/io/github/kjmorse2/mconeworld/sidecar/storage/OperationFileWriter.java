// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Durable writes to the {@code operations/} area, under the writer lock. It offers exactly three
 * primitives, all of which return only once their bytes are on stable storage ({@code fsync}), so a
 * returned call is a durable acknowledgement:
 *
 * <ul>
 *   <li>{@link #writeOnce}: create an immutable file (an operation plan), atomically
 *   <li>{@link #append}: append to an append-only file (a journal), checking that nobody else did
 *   <li>{@link #replace}: atomically replace a file whose exact current content is known (only for
 *       explicitly discarding a journal's torn tail)
 * </ul>
 *
 * <p>Every call first verifies the writer lock ({@code LOCK_LOST}). Paths are relative to {@code
 * operations/}. Nothing here deletes a file it did not create in the same call. A leftover {@code
 * .tmp} file from a crash is reported ({@code RECOVERY_REQUIRED}) and never removed automatically.
 *
 * <p>Directory entries are synced where the platform allows (ADR 0004 §4); where it does not, the
 * data is still synced and the write still happens.
 */
public final class OperationFileWriter {
  /** Suffix of the temporary file used by {@link #writeOnce} and {@link #replace}. */
  public static final String TEMP_SUFFIX = ".tmp";

  private final SidecarWriter writer;
  private final OperationFiles files;
  private final StorageFileSystem fs;

  OperationFileWriter(SidecarWriter writer) {
    this.writer = writer;
    this.files = writer.store().operationFiles();
    this.fs = writer.store().fs();
  }

  /** Read access to the same area. */
  public OperationFiles files() {
    return files;
  }

  /**
   * Creates {@code path} with {@code content}, atomically: the file either does not exist or has
   * the whole content. Idempotent for identical content.
   *
   * @return {@code true} if the file was created, {@code false} if it already held exactly {@code
   *     content}
   * @throws StorageException {@code DATA_FILE_CONFLICT} if it exists with other content, {@code
   *     RECOVERY_REQUIRED} if a temporary file from an interrupted write is present, {@code
   *     ATOMIC_MOVE_UNSUPPORTED}, or {@code LOCK_LOST}
   */
  public boolean writeOnce(SidecarPath path, byte[] content) throws IOException {
    writer.lock().verifyHeld();
    Path target = files.resolve(path);
    if (fs.exists(target)) {
      if (Arrays.equals(readUpTo(target, content.length + 1L), content)) {
        return false;
      }
      throw new StorageException(
          StorageIssueCodes.DATA_FILE_CONFLICT,
          "operations/" + path + " already exists with different content");
    }
    Path temp = requireNoTemp(path, target);
    ensureParents(target);
    writeNewDurably(temp, content);
    moveIntoPlace(temp, target);
    return true;
  }

  /**
   * Appends {@code bytes} to {@code path}, then syncs. The file must currently hold exactly {@code
   * expectedSize} bytes, which proves the caller's view is current. A missing file counts as size 0
   * and is created (with its parent directories).
   *
   * <p>If this throws, the file may hold none, some, or all of {@code bytes}: the caller must not
   * treat the append as acknowledged, and must re-read the file before trying again.
   *
   * @throws StorageException {@code BASE_CHANGED} if the size differs, or {@code LOCK_LOST}
   */
  public void append(SidecarPath path, long expectedSize, byte[] bytes) throws IOException {
    writer.lock().verifyHeld();
    Path target = files.resolve(path);
    if (!fs.exists(target)) {
      if (expectedSize != 0) {
        throw new StorageException(
            StorageIssueCodes.BASE_CHANGED,
            "operations/" + path + " no longer exists; expected " + expectedSize + " bytes");
      }
      ensureParents(target);
      writeNewDurably(target, bytes);
      syncDir(target.getParent());
      return;
    }
    if (!fs.isRegularFile(target)) {
      throw new StorageException(
          StorageIssueCodes.ROOT_INVALID, "operations/" + path + " is not a regular file");
    }
    long actual = fs.size(target);
    if (actual != expectedSize) {
      throw new StorageException(
          StorageIssueCodes.BASE_CHANGED,
          "operations/"
              + path
              + " holds "
              + actual
              + " bytes; the caller expected "
              + expectedSize
              + ". Re-read it before appending.");
    }
    try (StorageFileSystem.WritableFile out = fs.openAppend(target)) {
      out.write(bytes, 0, bytes.length);
      out.sync();
    }
  }

  /**
   * Atomically replaces {@code path}, whose current content must have checksum {@code
   * expectedCurrent}.
   *
   * @throws StorageException {@code BASE_CHANGED} if the current content differs, {@code
   *     RECOVERY_REQUIRED} if a temporary file is present, {@code ATOMIC_MOVE_UNSUPPORTED}, or
   *     {@code LOCK_LOST}
   */
  public void replace(SidecarPath path, Checksum expectedCurrent, byte[] content, long maxBytes)
      throws IOException {
    writer.lock().verifyHeld();
    Path target = files.resolve(path);
    Checksum actual = SidecarStore.measure(files.read(path, maxBytes));
    if (!actual.equals(expectedCurrent)) {
      throw new StorageException(
          StorageIssueCodes.BASE_CHANGED,
          "operations/" + path + " changed since it was read; re-read it first");
    }
    Path temp = requireNoTemp(path, target);
    writeNewDurably(temp, content);
    moveIntoPlace(temp, target);
  }

  private Path requireNoTemp(SidecarPath path, Path target) throws StorageException {
    Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
    if (fs.exists(temp)) {
      throw new StorageException(
          StorageIssueCodes.RECOVERY_REQUIRED,
          "operations/"
              + path
              + TEMP_SUFFIX
              + " is left over from an interrupted write. It is never removed automatically:"
              + " inspect it, then remove it by hand.");
    }
    return temp;
  }

  /** Creates, writes, and syncs a new file. On failure, removes it: this call created it. */
  private void writeNewDurably(Path file, byte[] content) throws IOException {
    try {
      try (StorageFileSystem.WritableFile out = fs.createNew(file)) {
        out.write(content, 0, content.length);
        out.sync();
      }
    } catch (IOException e) {
      deleteQuietly(file, e);
      throw e;
    }
  }

  private void moveIntoPlace(Path temp, Path target) throws IOException {
    try {
      fs.moveAtomically(temp, target);
    } catch (AtomicMoveNotSupportedException e) {
      deleteQuietly(temp, e);
      throw new StorageException(
          StorageIssueCodes.ATOMIC_MOVE_UNSUPPORTED,
          "The filesystem cannot rename atomically; refusing to write",
          e);
    } catch (IOException e) {
      deleteQuietly(temp, e);
      throw e;
    }
    syncDir(target.getParent());
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

  private void ensureParents(Path target) throws IOException {
    Path root = writer.store().root().path();
    List<Path> missing = new ArrayList<>();
    for (Path p = target.getParent();
        p != null && !p.equals(root) && !fs.exists(p);
        p = p.getParent()) {
      missing.add(0, p);
    }
    for (Path p : missing) {
      fs.createDirectory(p);
      syncDir(p.getParent());
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

  private byte[] readUpTo(Path file, long max) throws IOException {
    try (InputStream in = fs.openRead(file)) {
      return in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max));
    }
  }
}
