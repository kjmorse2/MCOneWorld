// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.lock;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.tool.world.testing.SyntheticWorld;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The probe sees a lock held in this JVM and in another process, and never alters the file. */
class SessionLockProbeTest {
  @TempDir Path temp;

  @Test
  void absentFreeAndHeldInThisJvm() throws Exception {
    Path world = SyntheticWorld.modern(temp.resolve("w")).levelDat(4903).dir();
    assertThat(SessionLockProbe.probe(world)).isEqualTo(SessionLockState.ABSENT);
    assertThat(Files.exists(world.resolve("session.lock")))
        .as("the probe never creates it")
        .isFalse();

    SyntheticWorld.modern(world).sessionLock();
    byte[] before = Files.readAllBytes(world.resolve("session.lock"));
    assertThat(SessionLockProbe.probe(world)).isEqualTo(SessionLockState.FREE);
    try (FileChannel channel =
            FileChannel.open(world.resolve("session.lock"), StandardOpenOption.WRITE);
        FileLock lock = channel.lock()) {
      assertThat(lock.isValid()).isTrue();
      assertThat(SessionLockProbe.probe(world)).isEqualTo(SessionLockState.HELD);
    }
    assertThat(SessionLockProbe.probe(world)).isEqualTo(SessionLockState.FREE);
    assertThat(Files.readAllBytes(world.resolve("session.lock"))).isEqualTo(before);
  }

  @Test
  void aLockHeldByAnotherProcessIsHeld() throws Exception {
    Path world = SyntheticWorld.modern(temp.resolve("w")).levelDat(4903).sessionLock().dir();
    Path ready = temp.resolve("ready");
    Process holder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                LockHolder.class.getName(),
                world.resolve("session.lock").toString(),
                ready.toString())
            .inheritIO()
            .start();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (!Files.exists(ready) && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertThat(Files.exists(ready)).as("the holder started").isTrue();
      assertThat(SessionLockProbe.probe(world)).isEqualTo(SessionLockState.HELD);
    } finally {
      holder.destroy();
      holder.waitFor(30, TimeUnit.SECONDS);
    }
  }

  @Test
  void aSymlinkedLockIsUnknown() throws Exception {
    Path world = SyntheticWorld.modern(temp.resolve("w")).levelDat(4903).dir();
    Files.createSymbolicLink(world.resolve("session.lock"), world.resolve("level.dat"));
    assertThat(SessionLockProbe.probe(world)).isEqualTo(SessionLockState.UNKNOWN);
    assertThat(SessionLockState.UNKNOWN.allowsWrites()).isFalse();
  }

  /** A separate process that holds the lock like a running server, until it is killed. */
  public static final class LockHolder {
    private LockHolder() {}

    public static void main(String[] args) throws Exception {
      try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
          FileLock lock = channel.lock()) {
        Files.createFile(Path.of(args[1]));
        Thread.sleep(TimeUnit.MINUTES.toMillis(5));
        lock.release();
      }
    }
  }
}
