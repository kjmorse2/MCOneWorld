// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.CLOCK;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.CONTENT_V1;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.INDEX_V1;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.INDEX_V2;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.OWNER;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.commitInitial;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.commitNext;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.store;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.writer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Failure injection at every {@link CommitStage}, both as an ordinary I/O failure (the transaction
 * rolls back) and as a simulated process crash (nothing rolls back; recovery must cope).
 */
class CommitFaultTest {
  /** Stages at which the new manifest is already committed. */
  private static final Set<CommitStage> POST_COMMIT =
      EnumSet.of(CommitStage.AFTER_MANIFEST_REPLACEMENT, CommitStage.DURING_CLEANUP);

  @TempDir Path root;

  @ParameterizedTest
  @EnumSource(CommitStage.class)
  void ioFailureAtAnyStageNeverLosesTheLastCommittedManifest(CommitStage stage) throws Exception {
    ManifestDocument base = commitInitial(root);
    FaultyFileSystem fs = FaultyFileSystem.failingAt(stage);
    SidecarStore store = store(root, fs);

    try (SidecarWriter writer = writer(store)) {
      if (POST_COMMIT.contains(stage)) {
        CommitResult result = commitNext(writer, base);
        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.cleanupComplete())
            .as("cleanup failure is reported, not thrown")
            .isFalse();
      } else {
        assertThatThrownBy(() -> commitNext(writer, base)).isInstanceOf(StorageException.class);
      }
    }
    assertThat(fs.openHandles.get()).as("handles closed after %s", stage).isZero();

    SidecarStore fresh = store(root, JdkStorageFileSystem.INSTANCE);
    long expectedRevision = POST_COMMIT.contains(stage) ? 1 : 0;
    assertThat(fresh.readManifest().document().revision()).isEqualTo(expectedRevision);
    if (!POST_COMMIT.contains(stage)) {
      assertThat(fresh.readManifest().document()).isEqualTo(base);
      assertThat(Files.exists(root.resolve(INDEX_V2.value())))
          .as("rolled back data file")
          .isFalse();
      assertThat(fresh.inspect().findings()).as("rollback leaves nothing behind").isEmpty();
    }
    assertThat(Files.readAllBytes(root.resolve(INDEX_V1.value()))).isEqualTo(CONTENT_V1);
    recoverTwiceAndAssertClean(fresh);
  }

  @ParameterizedTest
  @EnumSource(CommitStage.class)
  void crashAtAnyStageIsDetectableAndRecoverable(CommitStage stage) throws Exception {
    ManifestDocument base = commitInitial(root);
    FaultyFileSystem fs = FaultyFileSystem.crashingAt(stage);
    SidecarStore store = store(root, fs);
    SidecarWriter writer = writer(store);
    assertThatThrownBy(() -> commitNext(writer, base)).isInstanceOf(SimulatedCrash.class);
    writer.lock().abandonLikeACrashedProcess(); // the process is gone: no rollback, lock file left

    SidecarStore fresh = store(root, JdkStorageFileSystem.INSTANCE);
    long expectedRevision = POST_COMMIT.contains(stage) ? 1 : 0;
    assertThat(fresh.readManifest().document().revision())
        .as("readable after crash at %s", stage)
        .isEqualTo(expectedRevision);

    RecoveryReport report = fresh.inspect();
    assertThat(report.lock()).isNotNull();
    assertThat(report.lock().provablyStale()).isTrue();
    assertThat(report.findings()).as("leftovers detected after crash at %s", stage).isNotEmpty();
    assertThat(report.needsManualAttention()).isFalse();

    // The crashed writer's lock is provably stale: refused by default, taken over on request.
    assertThatThrownBy(() -> writer(fresh))
        .isInstanceOfSatisfying(
            LockException.class, e -> assertThat(e.code()).isEqualTo(StorageIssueCodes.LOCK_STALE));
    try (SidecarWriter recovering =
        fresh.openWriter(OWNER, CLOCK, StaleLockPolicy.TAKE_OVER_PROVABLY_STALE)) {
      RecoveryReport after = recovering.recover();
      assertThat(after.findings()).isEmpty();
      assertThat(recovering.recover().findings()).as("recovery is idempotent").isEmpty();
      // Recovery never changes the committed manifest.
      assertThat(fresh.readManifest().document().revision()).isEqualTo(expectedRevision);
      // Orphans of an uncommitted transaction are removed; committed data stays.
      assertThat(Files.exists(root.resolve(INDEX_V2.value())))
          .isEqualTo(POST_COMMIT.contains(stage));
      // And the sidecar is writable again.
      ManifestDocument current = fresh.readManifest().document();
      if (!POST_COMMIT.contains(stage)) {
        assertThat(commitNext(recovering, current).revision()).isEqualTo(1);
      }
    }
    assertThat(Files.exists(root.resolve(SidecarRoot.STAGING))).isFalse();
    assertThat(Files.exists(root.resolve(SidecarRoot.LOCK_OWNER))).isFalse();
  }

  private static void recoverTwiceAndAssertClean(SidecarStore store) throws Exception {
    try (SidecarWriter writer = writer(store)) {
      assertThat(writer.recover().findings()).isEmpty();
      assertThat(writer.recover().findings()).isEmpty();
    }
    assertThat(store.inspect().isClean()).isTrue();
  }
}
