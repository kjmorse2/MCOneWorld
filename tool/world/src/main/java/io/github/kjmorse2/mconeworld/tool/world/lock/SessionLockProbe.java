// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.lock;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Tests the world's {@code session.lock}. Minecraft keeps an exclusive OS lock on it for as long as
 * a world is open, so taking and immediately releasing the same lock tells whether the world is in
 * use. The file is opened for writing only because an exclusive lock requires it; nothing is
 * written, and the file is never created.
 *
 * <p>The answer is a snapshot: a server can start right after. Writers probe again immediately
 * before mutating and hold the sidecar writer lock throughout.
 */
public final class SessionLockProbe {
  /** The lock file's name in the world directory. */
  public static final String FILE_NAME = "session.lock";

  private SessionLockProbe() {}

  public static SessionLockState probe(Path worldDir) {
    Path file = worldDir.resolve(FILE_NAME);
    if (Files.isSymbolicLink(file)) {
      return SessionLockState.UNKNOWN;
    }
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      return SessionLockState.ABSENT;
    }
    try (FileChannel channel =
        FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException e) {
        return SessionLockState.HELD; // held by this JVM
      }
      if (lock == null) {
        return SessionLockState.HELD;
      }
      lock.release();
      return SessionLockState.FREE;
    } catch (IOException e) {
      // No permission, a mandatory lock (Windows), or an I/O error: the state is unknown.
      return SessionLockState.UNKNOWN;
    }
  }
}
