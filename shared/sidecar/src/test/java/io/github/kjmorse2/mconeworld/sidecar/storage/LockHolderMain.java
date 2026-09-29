// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Child-process helper for {@link SidecarLockTest}: acquires the writer lock on {@code args[0]},
 * inspects its own lock a few times, prints {@code LOCKED}, and then holds the lock until stdin
 * reaches end-of-file. The test kills the process to simulate a crash.
 */
public final class LockHolderMain {
  private LockHolderMain() {}

  public static void main(String[] args) throws Exception {
    SidecarRoot root = SidecarRoot.open(Path.of(args[0]));
    try (SidecarLock lock =
        SidecarLock.acquire(
            root, new LockOwner("child-process", "1"), Clock.systemUTC(), StaleLockPolicy.REFUSE)) {
      // Exercise the holder-side operations that, if they reopened writer.lock, would silently
      // drop this process's POSIX lock (the regression SidecarLockTest guards against).
      for (int i = 0; i < 3; i++) {
        SidecarLock.inspect(root);
        lock.verifyHeld();
      }
      System.out.println("LOCKED " + lock.info().lockId());
      System.out.flush();
      while (System.in.read() != -1) {
        // hold the lock until the parent closes stdin
      }
    }
  }
}
