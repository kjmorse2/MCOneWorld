// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.backup;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupEntry;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupEntryPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupFileWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageFileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.LongConsumer;

/** Streaming copies and hashes of world files. Interruptible between buffers. */
public final class FileCopy {
  private static final int BUFFER = 1 << 20;

  private FileCopy() {}

  /**
   * Copies {@code source} into a backup as {@code entry}, hashing it on the way, and syncs it.
   *
   * @param copied called with the bytes of this file copied so far
   * @throws InterruptedIOException if the thread is interrupted
   */
  public static BackupEntry copy(
      BackupFileWriter out, Path source, BackupEntryPath entry, LongConsumer copied)
      throws IOException {
    MessageDigest digest = sha256();
    long size = 0;
    byte[] buffer = new byte[BUFFER];
    try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
        StorageFileSystem.WritableFile file = out.createNew(entry)) {
      for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
        checkInterrupted();
        digest.update(buffer, 0, n);
        file.write(buffer, 0, n);
        size += n;
        copied.accept(size);
      }
      file.sync();
    }
    return new BackupEntry(
        entry, size, Checksum.ofDigest(Checksum.Algorithm.SHA_256, digest.digest()));
  }

  /** The SHA-256 of {@code in}'s remaining bytes. */
  public static Checksum sha256(InputStream in) throws IOException {
    MessageDigest digest = sha256();
    byte[] buffer = new byte[BUFFER];
    for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
      checkInterrupted();
      digest.update(buffer, 0, n);
    }
    return Checksum.ofDigest(Checksum.Algorithm.SHA_256, digest.digest());
  }

  /** The SHA-256 of the regular file {@code file}, not following a symbolic link. */
  public static Checksum sha256(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      return sha256(in);
    }
  }

  private static void checkInterrupted() throws InterruptedIOException {
    if (Thread.interrupted()) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("The copy was cancelled");
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
