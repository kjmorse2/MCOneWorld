// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.CLOCK;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.OWNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadLimits;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class SidecarLockTest {
  @TempDir Path dir;

  private SidecarRoot root() throws StorageException {
    return SidecarRoot.open(dir);
  }

  private SidecarLock acquire(StaleLockPolicy policy) throws StorageException {
    return SidecarLock.acquire(root(), OWNER, CLOCK, policy);
  }

  @Test
  void secondWriterInTheSameProcessIsRefusedWithOwnerDetails() throws Exception {
    try (SidecarLock first = acquire(StaleLockPolicy.REFUSE)) {
      assertThatThrownBy(() -> acquire(StaleLockPolicy.TAKE_OVER_PROVABLY_STALE))
          .isInstanceOfSatisfying(
              LockException.class,
              e -> {
                assertThat(e.code()).isEqualTo(StorageIssueCodes.LOCK_CONTENDED);
                assertThat(e.owner()).isEqualTo(first.info());
              });
      LockStatus status = SidecarLock.inspect(root());
      assertThat(status).isNotNull();
      assertThat(status.held()).isTrue();
      assertThat(status.provablyStale()).isFalse();
    }
    assertThat(dir.resolve(SidecarRoot.LOCK_OWNER)).doesNotExist();
    try (SidecarLock again = acquire(StaleLockPolicy.REFUSE)) {
      assertThat(again.info().lockId()).isNotNull();
    }
  }

  @RepeatedTest(20)
  void concurrentAcquirersNeverBothSucceed() throws Exception {
    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch holdUntil = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try {
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try (SidecarLock lock = acquire(StaleLockPolicy.TAKE_OVER_PROVABLY_STALE)) {
                    holdUntil.await(
                        5, TimeUnit.SECONDS); // keep holding so every other thread must fail
                    return lock != null;
                  } catch (LockException e) {
                    return false;
                  }
                }));
      }
      start.countDown();
      Thread.sleep(200);
      holdUntil.countDown();
      int winners = 0;
      for (Future<Boolean> r : results) {
        winners += r.get(10, TimeUnit.SECONDS) ? 1 : 0;
      }
      assertThat(winners).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(dir.resolve(SidecarRoot.LOCK_OWNER)).doesNotExist();
  }

  @Test
  void staleLockIsRefusedByDefaultAndTakenOverOnlyOnRequest() throws Exception {
    SidecarLock crashed = acquire(StaleLockPolicy.REFUSE);
    crashed.abandonLikeACrashedProcess();

    LockStatus status = SidecarLock.inspect(root());
    assertThat(status).isNotNull();
    assertThat(status.provablyStale()).isTrue();
    assertThat(status.info()).isEqualTo(crashed.info());

    assertThatThrownBy(() -> acquire(StaleLockPolicy.REFUSE))
        .isInstanceOfSatisfying(
            LockException.class,
            e -> {
              assertThat(e.code()).isEqualTo(StorageIssueCodes.LOCK_STALE);
              assertThat(e.owner()).isEqualTo(crashed.info());
            });
    try (SidecarLock takenOver = acquire(StaleLockPolicy.TAKE_OVER_PROVABLY_STALE)) {
      assertThat(takenOver.info().lockId()).isNotEqualTo(crashed.info().lockId());
      takenOver.verifyHeld();
    }
  }

  @Test
  void unreadableOwnerMetadataIsNeverTreatedAsStale() throws Exception {
    Files.writeString(dir.resolve(SidecarRoot.LOCK_OWNER), "{ half-written");
    assertThatThrownBy(() -> acquire(StaleLockPolicy.TAKE_OVER_PROVABLY_STALE))
        .isInstanceOfSatisfying(
            LockException.class,
            e -> assertThat(e.code()).isEqualTo(StorageIssueCodes.LOCK_CONTENDED));
    LockStatus status = SidecarLock.inspect(root());
    assertThat(status).isNotNull();
    assertThat(status.info()).isNull();
    assertThat(status.provablyStale()).isFalse();
    assertThat(dir.resolve(SidecarRoot.LOCK_OWNER))
        .hasContent("{ half-written"); // never auto-deleted
  }

  @Test
  void externalRemovalIsDetectedAsLockLost() throws Exception {
    try (SidecarLock lock = acquire(StaleLockPolicy.REFUSE)) {
      lock.verifyHeld();
      Files.delete(dir.resolve(SidecarRoot.LOCK_OWNER));
      assertThatThrownBy(lock::verifyHeld)
          .isInstanceOfSatisfying(
              StorageException.class,
              e -> assertThat(e.code()).isEqualTo(StorageIssueCodes.LOCK_LOST));
    }
  }

  @Test
  void closeIsIdempotentAndLeavesOnlyThePermanentLockTarget() throws Exception {
    SidecarLock lock = acquire(StaleLockPolicy.REFUSE);
    assertThat(dir.resolve(SidecarRoot.LOCK_OWNER)).exists();
    lock.close();
    lock.close();
    assertThat(dir.resolve(SidecarRoot.LOCK_OWNER)).doesNotExist();
    assertThat(dir.resolve(SidecarRoot.LOCK)).exists().isEmptyFile();
    assertThat(SidecarLock.inspect(root())).isNull();
  }

  @Test
  void inspectingFromTheHoldingProcessDoesNotReleaseTheLock() throws Exception {
    // Regression: on POSIX, closing any descriptor of a locked file drops the process's fcntl
    // lock. Inspection and re-acquisition inside the holding JVM must never open writer.lock.
    try (SidecarLock lock = acquire(StaleLockPolicy.REFUSE)) {
      for (int i = 0; i < 5; i++) {
        assertThat(SidecarLock.inspect(root()).held()).isTrue();
        assertThatThrownBy(() -> acquire(StaleLockPolicy.TAKE_OVER_PROVABLY_STALE))
            .isInstanceOf(LockException.class);
      }
      lock.verifyHeld();
    }
  }

  @Test
  void lockMetadataContainsOnlyNonSecretFields() throws Exception {
    try (SidecarLock lock = acquire(StaleLockPolicy.REFUSE)) {
      byte[] bytes = Files.readAllBytes(dir.resolve(SidecarRoot.LOCK_OWNER));
      JsonObject json =
          (JsonObject) new JsonReader(new JsonReadLimits(4096, 4, 256, 16, 16)).read(bytes);
      assertThat(json.members().keySet())
          .containsExactlyInAnyOrder(
              "format", "version", "lockId", "tool", "toolVersion", "host", "pid", "acquiredAt");
      assertThat(new String(bytes, StandardCharsets.UTF_8))
          .doesNotContain(dir.toString())
          .doesNotContain(System.getProperty("user.name"));
      assertThat(lock.info().pid()).isEqualTo(ProcessHandle.current().pid());
    }
  }

  // --- across processes ----------------------------------------------------------------------

  private Process startHolder() throws Exception {
    String java =
        ProcessHandle.current()
            .info()
            .command()
            .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    Process child =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                LockHolderMain.class.getName(),
                dir.toString())
            .redirectErrorStream(true)
            .start();
    BufferedReader out =
        new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
    String line = out.readLine();
    assertThat(line).as("child acquired the lock").startsWith("LOCKED");
    return child;
  }

  @Test
  @Timeout(60)
  void anotherProcessHoldingTheLockExcludesThisOne() throws Exception {
    Process child = startHolder();
    try {
      assertThatThrownBy(() -> acquire(StaleLockPolicy.TAKE_OVER_PROVABLY_STALE))
          .isInstanceOfSatisfying(
              LockException.class,
              e -> {
                assertThat(e.code()).isEqualTo(StorageIssueCodes.LOCK_CONTENDED);
                assertThat(e.owner()).isNotNull();
                assertThat(e.owner().tool()).isEqualTo("child-process");
                assertThat(e.owner().pid()).isEqualTo(child.pid());
              });
      assertThat(SidecarLock.inspect(root()).held()).isTrue();
      // The child's lock survives repeated probing from this process.
      for (int i = 0; i < 5; i++) {
        assertThatThrownBy(() -> acquire(StaleLockPolicy.TAKE_OVER_PROVABLY_STALE))
            .isInstanceOf(LockException.class);
      }
    } finally {
      child.getOutputStream().close(); // graceful release
      assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(dir.resolve(SidecarRoot.LOCK_OWNER)).doesNotExist();
    try (SidecarLock mine = acquire(StaleLockPolicy.REFUSE)) {
      mine.verifyHeld();
    }
  }

  @Test
  @Timeout(60)
  void killedProcessLeavesAProvablyStaleLock() throws Exception {
    Process child = startHolder();
    long childPid = child.pid();
    child.destroyForcibly();
    assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();

    LockStatus status = SidecarLock.inspect(root());
    assertThat(status).isNotNull();
    assertThat(status.provablyStale()).isTrue();
    assertThat(status.info().pid()).isEqualTo(childPid);

    assertThatThrownBy(() -> acquire(StaleLockPolicy.REFUSE))
        .isInstanceOfSatisfying(
            LockException.class, e -> assertThat(e.code()).isEqualTo(StorageIssueCodes.LOCK_STALE));
    try (SidecarLock mine = acquire(StaleLockPolicy.TAKE_OVER_PROVABLY_STALE)) {
      mine.verifyHeld();
    }
  }
}
