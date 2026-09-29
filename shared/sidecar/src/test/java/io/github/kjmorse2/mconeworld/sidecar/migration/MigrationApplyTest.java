// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.backupId;
import static io.github.kjmorse2.mconeworld.sidecar.migration.TestMigrations.v09;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestCodec;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestException;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestIssueCodes;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestLimits;
import io.github.kjmorse2.mconeworld.sidecar.storage.CommitStage;
import io.github.kjmorse2.mconeworld.sidecar.storage.JdkStorageFileSystem;
import io.github.kjmorse2.mconeworld.sidecar.storage.LockOwner;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageFileSystem;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageIssueCodes;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MigrationApplyTest {
  private static final LockOwner OWNER = new LockOwner("migration-test", "0");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  @TempDir Path sidecar;
  @TempDir Path backups;

  private MigrationRegistry registry;
  private Path backupCopy;

  @BeforeEach
  void setUp() throws Exception {
    Files.write(sidecar.resolve(SidecarRoot.MANIFEST), v09());
    backupCopy = Files.write(backups.resolve("manifest-before-migration.json"), v09());
    registry = MigrationRegistry.of(List.of(new SyntheticManifest09To10()));
  }

  private SidecarStore store(StorageFileSystem fs) throws StorageException {
    return SidecarStore.open(SidecarRoot.open(sidecar, fs), ManifestLimits.DEFAULT, fs);
  }

  private SidecarWriter writer(SidecarStore store) throws StorageException {
    return store.openWriter(OWNER, CLOCK, StaleLockPolicy.REFUSE);
  }

  private MigrationPlan plan() throws Exception {
    return new MigrationPlanner(registry, ManifestLimits.DEFAULT)
        .plan(store(JdkStorageFileSystem.INSTANCE), ManifestCodec.CURRENT);
  }

  private VerifiedBackup backup() throws Exception {
    return VerifiedBackup.verify(backupId(1), backupCopy, 1 << 20);
  }

  private void assertSourceUnchanged() throws Exception {
    assertThat(Files.readAllBytes(sidecar.resolve(SidecarRoot.MANIFEST))).isEqualTo(v09());
  }

  private static void assertCode(Executable action, IssueCode code) {
    assertThatThrownBy(action::execute)
        .satisfies(
            e -> {
              IssueCode actual =
                  e instanceof MigrationException m ? m.code() : ((StorageException) e).code();
              assertThat(actual).isEqualTo(code);
            });
  }

  @Test
  void validApplyCommitsTheMigratedManifestAndItsReport() throws Exception {
    MigrationPlan plan = plan();
    MigrationResult result;
    SidecarStore store = store(JdkStorageFileSystem.INSTANCE);
    try (SidecarWriter writer = writer(store)) {
      result = new MigrationRunner(registry, ManifestLimits.DEFAULT).apply(writer, plan, backup());
    }
    assertThat(result.commit().revision()).isEqualTo(4);
    var current = store.readManifest();
    assertThat(current.document().schemaVersion()).isEqualTo(ManifestCodec.CURRENT);
    assertThat(current.document().revision()).isEqualTo(4);
    assertThat(current.document().files())
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.role()).isEqualTo("migration-report");
              assertThat(f.path()).isEqualTo(result.reportPath());
            });
    String report = Files.readString(sidecar.resolve(result.reportPath().value()));
    assertThat(report)
        .contains("\"format\": \"mconeworld-migration-report\"")
        .contains("\"id\": \"manifest-0.9-to-1.0\"")
        .contains("\"sourceChecksum\": \"" + plan.report().sourceChecksum() + "\"")
        .contains(backupId(1).toString())
        .doesNotContain(backups.toString()); // machine-specific backup path is never persisted
    // The legacy manifest is kept as the last-known-good copy.
    assertThat(Files.readAllBytes(sidecar.resolve(SidecarRoot.PREVIOUS_MANIFEST))).isEqualTo(v09());
    assertThat(store.inspect().findings()).isEmpty();
  }

  @Test
  void nothingEverMigratesImplicitly() throws Exception {
    SidecarStore store = store(JdkStorageFileSystem.INSTANCE);
    assertThatThrownBy(store::readManifest)
        .isInstanceOfSatisfying(
            ManifestException.class,
            e -> assertThat(e.issues().has(ManifestIssueCodes.UNSUPPORTED_VERSION)).isTrue());
    try (SidecarWriter writer = writer(store)) {
      assertCode(
          () -> writer.begin(3L),
          StorageIssueCodes.RECOVERY_REQUIRED); // unrelated writes refuse, never migrate
    }
    assertSourceUnchanged();
  }

  @Test
  void planningIsSideEffectFree() throws Exception {
    StorageFileSystem counting = StorageTestAccess.counting();
    new MigrationPlanner(registry, ManifestLimits.DEFAULT)
        .plan(store(counting), ManifestCodec.CURRENT);
    assertThat(StorageTestAccess.mutations(counting)).isZero();
    assertThat(StorageTestAccess.openHandles(counting)).isZero();
    try (var files = Files.list(sidecar)) {
      assertThat(files).containsExactly(sidecar.resolve(SidecarRoot.MANIFEST));
    }
  }

  @Test
  void backupEvidenceIsRequiredAndChecked() throws Exception {
    MigrationPlan plan = plan();
    MigrationRunner runner = new MigrationRunner(registry, ManifestLimits.DEFAULT);
    try (SidecarWriter writer = writer(store(JdkStorageFileSystem.INSTANCE))) {
      // A backup of something else.
      Path other = Files.writeString(backups.resolve("other.json"), "{}");
      VerifiedBackup wrong = VerifiedBackup.verify(backupId(2), other, 1 << 20);
      assertCode(() -> runner.apply(writer, plan, wrong), MigrationIssueCodes.BACKUP_INVALID);
      // A "backup" inside the sidecar it is supposed to protect.
      Path inside = Files.write(sidecar.resolve("copy.json"), v09());
      VerifiedBackup internal = VerifiedBackup.verify(backupId(3), inside, 1 << 20);
      Files.delete(inside);
      assertCode(() -> runner.apply(writer, plan, internal), MigrationIssueCodes.BACKUP_INVALID);
      // A backup that was valid when verified but changed or vanished before apply.
      VerifiedBackup good = backup();
      Files.writeString(backupCopy, "tampered");
      assertCode(() -> runner.apply(writer, plan, good), MigrationIssueCodes.BACKUP_INVALID);
      Files.delete(backupCopy);
      assertCode(() -> runner.apply(writer, plan, good), MigrationIssueCodes.BACKUP_INVALID);
    }
    assertSourceUnchanged();
  }

  @Test
  void sourceChangedBetweenPlanAndApply() throws Exception {
    MigrationPlan plan = plan();
    byte[] edited =
        new String(v09(), java.nio.charset.StandardCharsets.UTF_8)
            .replace("Genesis\"", "Genesis!\"")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(sidecar.resolve(SidecarRoot.MANIFEST), edited);
    try (SidecarWriter writer = writer(store(JdkStorageFileSystem.INSTANCE))) {
      assertCode(
          () -> new MigrationRunner(registry, ManifestLimits.DEFAULT).apply(writer, plan, backup()),
          MigrationIssueCodes.SOURCE_CHANGED);
    }
    assertThat(Files.readAllBytes(sidecar.resolve(SidecarRoot.MANIFEST))).isEqualTo(edited);
  }

  @Test
  void nondeterministicMigrationIsNeverCommitted() throws Exception {
    MigrationRegistry flaky = MigrationRegistry.of(List.of(TestMigrations.nondeterministic()));
    MigrationPlan plan =
        new MigrationPlanner(flaky, ManifestLimits.DEFAULT).plan(v09(), ManifestCodec.CURRENT);
    try (SidecarWriter writer = writer(store(JdkStorageFileSystem.INSTANCE))) {
      assertCode(
          () -> new MigrationRunner(flaky, ManifestLimits.DEFAULT).apply(writer, plan, backup()),
          MigrationIssueCodes.NONDETERMINISTIC);
    }
    assertSourceUnchanged();
  }

  private static final EnumSet<CommitStage> POST_COMMIT =
      EnumSet.of(CommitStage.AFTER_MANIFEST_REPLACEMENT, CommitStage.DURING_CLEANUP);

  @ParameterizedTest
  @EnumSource(CommitStage.class)
  void storageFailureAtAnyStageLeavesTheSourceUnchanged(CommitStage stage) throws Throwable {
    MigrationPlan plan = plan();
    StorageFileSystem fs = StorageTestAccess.faulty(stage, false);
    try (SidecarWriter writer = writer(store(fs))) {
      Executable apply =
          () -> new MigrationRunner(registry, ManifestLimits.DEFAULT).apply(writer, plan, backup());
      if (POST_COMMIT.contains(stage)) {
        apply.execute(); // committed; cleanup failure is reported in the result, not thrown
      } else {
        assertThatThrownBy(apply::execute).isInstanceOf(StorageException.class);
      }
    }
    assertThat(StorageTestAccess.openHandles(fs)).isZero();
    if (POST_COMMIT.contains(stage)) {
      assertThat(store(JdkStorageFileSystem.INSTANCE).readManifest().document().revision())
          .isEqualTo(4);
    } else {
      assertSourceUnchanged();
      assertThat(sidecar.resolve(".staging")).doesNotExist();
    }
  }

  @ParameterizedTest
  @EnumSource(CommitStage.class)
  void crashAtAnyStageIsRecoverableAndTheMigrationCanBeRetried(CommitStage stage) throws Exception {
    MigrationPlan plan = plan();
    StorageFileSystem fs = StorageTestAccess.faulty(stage, true);
    SidecarWriter crashed = writer(store(fs));
    assertThatThrownBy(
            () ->
                new MigrationRunner(registry, ManifestLimits.DEFAULT)
                    .apply(crashed, plan, backup()))
        .satisfies(t -> assertThat(StorageTestAccess.isSimulatedCrash(t)).isTrue());
    StorageTestAccess.crash(crashed);

    SidecarStore store = store(JdkStorageFileSystem.INSTANCE);
    if (!POST_COMMIT.contains(stage)) {
      assertSourceUnchanged();
    }
    try (SidecarWriter writer =
        store.openWriter(OWNER, CLOCK, StaleLockPolicy.TAKE_OVER_PROVABLY_STALE)) {
      writer.recover();
      assertThat(writer.recover().findings())
          .as("only the (still legacy) manifest itself may remain as a finding")
          .allMatch(f -> f.code().equals(StorageIssueCodes.RECOVERY_MANIFEST_UNREADABLE));
      if (!POST_COMMIT.contains(stage)) {
        assertSourceUnchanged();
        MigrationResult retried =
            new MigrationRunner(registry, ManifestLimits.DEFAULT).apply(writer, plan(), backup());
        assertThat(retried.commit().revision()).isEqualTo(4);
      }
    }
    assertThat(store.readManifest().document().revision()).isEqualTo(4);
    assertThat(store.inspect().findings()).isEmpty();
  }
}
