// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.CONTENT_V1;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.CONTENT_V2;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.INDEX_V1;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.INDEX_V2;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.commitInitial;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.commitNext;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.next;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.store;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.writer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import io.github.kjmorse2.mconeworld.sidecar.manifest.FileReference;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class StorageBehaviourTest {
  @TempDir Path root;

  private static void assertCode(Executable action, IssueCode code) {
    assertThatThrownBy(action::execute)
        .isInstanceOfSatisfying(StorageException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  private ManifestDocument current() throws Exception {
    return store(root, JdkStorageFileSystem.INSTANCE).readManifest().document();
  }

  private void assertStillAt(ManifestDocument expected) throws Exception {
    assertThat(current()).isEqualTo(expected);
    assertThat(store(root, JdkStorageFileSystem.INSTANCE).inspect().findings()).isEmpty();
  }

  @Test
  void commitKeepsThePreviousManifestAsLastKnownGood() throws Exception {
    ManifestDocument base = commitInitial(root);
    SidecarStore store = store(root, JdkStorageFileSystem.INSTANCE);
    try (SidecarWriter writer = writer(store)) {
      CommitResult result = commitNext(writer, base);
      assertThat(result.cleanupComplete()).isTrue();
      assertThat(result.directorySyncSupported())
          .isTrue(); // Linux CI; see ADR 0004 for other platforms
    }
    assertThat(store.readManifest().document().revision()).isEqualTo(1);
    assertThat(store.readPreviousManifest().document()).isEqualTo(base);
    assertThat(Files.readAllBytes(root.resolve(INDEX_V2.value()))).isEqualTo(CONTENT_V2);
    // Old data stays: committed data files are immutable and never overwritten.
    assertThat(Files.readAllBytes(root.resolve(INDEX_V1.value()))).isEqualTo(CONTENT_V1);
  }

  @Test
  void stagedContentCorruptedBeforeVerificationIsDetected() throws Exception {
    ManifestDocument base = commitInitial(root);
    FaultyFileSystem fs = new FaultyFileSystem();
    fs.onStage =
        (stage, subject) -> {
          if (stage == CommitStage.BEFORE_CHECKSUM_VERIFICATION && subject != null) {
            try (var files = Files.list(subject)) {
              for (Path p :
                  files.filter(f -> f.getFileName().toString().startsWith("data-")).toList()) {
                Files.writeString(p, "bit rot"); // same process, outside the storage seam
              }
            }
          }
        };
    try (SidecarWriter writer = writer(store(root, fs))) {
      assertCode(() -> commitNext(writer, base), StorageIssueCodes.CHECKSUM_MISMATCH);
    }
    assertStillAt(base);
  }

  @Test
  void manifestMustReferenceStagedContentExactly() throws Exception {
    ManifestDocument base = commitInitial(root);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      SidecarTransaction tx = writer.begin(0L);
      StagedFile staged = tx.stage(INDEX_V2, new ByteArrayInputStream(CONTENT_V2), 1 << 20);
      ManifestDocument wrongChecksum =
          next(
              base,
              new StagedFile(staged.target(), ManifestFixtures.CHECKSUM_B, staged.sizeBytes()));
      assertCode(() -> tx.commit(wrongChecksum), StorageIssueCodes.CHECKSUM_MISMATCH);
    }
    assertStillAt(base);
  }

  @Test
  void unstagedMissingReferenceIsRejected() throws Exception {
    ManifestDocument base = commitInitial(root);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      SidecarTransaction tx = writer.begin(0L);
      ManifestDocument dangling =
          base.withFile(
                  new FileReference(
                      "report",
                      null,
                      SidecarPath.of("reports/missing.json"),
                      ManifestFixtures.CHECKSUM_A,
                      3))
              .nextRevision();
      assertCode(() -> tx.commit(dangling), StorageIssueCodes.REFERENCE_UNVERIFIED);
    }
    assertStillAt(base);
  }

  @Test
  void stagedButUnreferencedFileIsRejected() throws Exception {
    ManifestDocument base = commitInitial(root);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      SidecarTransaction tx = writer.begin(0L);
      tx.stage(INDEX_V2, new ByteArrayInputStream(CONTENT_V2), 1 << 20);
      assertCode(() -> tx.commit(base.nextRevision()), StorageIssueCodes.TRANSACTION_INVALID);
    }
    assertThat(Files.exists(root.resolve(INDEX_V2.value()))).isFalse();
    assertStillAt(base);
  }

  @Test
  void committedDataFilesAreNeverOverwritten() throws Exception {
    ManifestDocument base = commitInitial(root);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      SidecarTransaction tx = writer.begin(0L);
      StagedFile staged =
          tx.stage(
              INDEX_V1, new ByteArrayInputStream(CONTENT_V2), 1 << 20); // same path, new content
      assertCode(() -> tx.commit(next(base, staged)), StorageIssueCodes.DATA_FILE_CONFLICT);
    }
    assertThat(Files.readAllBytes(root.resolve(INDEX_V1.value()))).isEqualTo(CONTENT_V1);
    assertStillAt(base);
  }

  @Test
  void restagingIdenticalContentIsIdempotent() throws Exception {
    ManifestDocument base = commitInitial(root);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      SidecarTransaction tx = writer.begin(0L);
      StagedFile staged = tx.stage(INDEX_V1, new ByteArrayInputStream(CONTENT_V1), 1 << 20);
      assertThat(tx.commit(next(base, staged)).revision()).isEqualTo(1);
    }
  }

  @Test
  void reservedPathsAreRefused() throws Exception {
    ManifestDocument base = commitInitial(root);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      for (String reserved :
          List.of(
              "manifest.json",
              "manifest.previous.json",
              "writer.lock",
              "writer.owner.json",
              ".staging/x",
              "operations/x")) {
        SidecarTransaction tx = writer.begin(0L);
        assertCode(
            () -> tx.stage(SidecarPath.of(reserved), new ByteArrayInputStream(new byte[1]), 10),
            StorageIssueCodes.RESERVED_PATH);
      }
      SidecarTransaction tx = writer.begin(0L);
      ManifestDocument referencingLock =
          base.withFile(
                  new FileReference(
                      "report",
                      null,
                      SidecarPath.of("writer.lock"),
                      ManifestFixtures.CHECKSUM_A,
                      1))
              .nextRevision();
      assertCode(() -> tx.commit(referencingLock), StorageIssueCodes.RESERVED_PATH);
    }
    assertStillAt(base);
  }

  @Test
  void revisionAndBaseChecks() throws Exception {
    ManifestDocument base = commitInitial(root);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      assertCode(() -> writer.begin(7L), StorageIssueCodes.BASE_CHANGED);
      assertCode(() -> writer.begin(null), StorageIssueCodes.BASE_CHANGED);
      SidecarTransaction tx = writer.begin(0L);
      assertCode(() -> tx.commit(base), StorageIssueCodes.TRANSACTION_INVALID); // revision not +1
      SidecarTransaction tx2 = writer.begin(0L);
      ManifestDocument otherArchive =
          ManifestDocument.create(
                  io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.archiveId(2),
                  base.createdAt())
              .nextRevision();
      assertCode(() -> tx2.commit(otherArchive), StorageIssueCodes.TRANSACTION_INVALID);
    }
    assertStillAt(base);
  }

  @Test
  void finishedTransactionsCannotBeReused() throws Exception {
    ManifestDocument base = commitInitial(root);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      SidecarTransaction tx = writer.begin(0L);
      tx.abort();
      tx.abort(); // idempotent
      assertCode(() -> tx.commit(base.nextRevision()), StorageIssueCodes.TRANSACTION_INVALID);
    }
  }

  @Test
  void oversizedStagingIsRejected() throws Exception {
    ManifestDocument base = commitInitial(root);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      SidecarTransaction tx = writer.begin(0L);
      assertCode(
          () -> tx.stage(INDEX_V2, new ByteArrayInputStream(new byte[100]), 99),
          StorageIssueCodes.TOO_LARGE);
    }
    assertStillAt(base);
  }

  @Test
  void diskFullDuringContentWriteRollsBack() throws Exception {
    ManifestDocument base = commitInitial(root);
    FaultyFileSystem fs = new FaultyFileSystem();
    fs.capacityBytes = 400; // enough for the intent record, not for the data plus manifest
    try (SidecarWriter writer = writer(store(root, fs))) {
      SidecarTransaction tx = writer.begin(0L);
      assertCode(
          () -> tx.stage(INDEX_V2, new ByteArrayInputStream(new byte[4096]), 1 << 20),
          StorageIssueCodes.IO_FAILURE);
    }
    assertThat(fs.openHandles.get()).isZero();
    assertStillAt(base);
  }

  @Test
  void unavailableAtomicMoveIsRefusedNotWorkedAround() throws Exception {
    ManifestDocument base = commitInitial(root);
    FaultyFileSystem fs = new FaultyFileSystem();
    fs.atomicMoveUnsupported = true;
    try (SidecarWriter writer = writer(store(root, fs))) {
      assertCode(() -> commitNext(writer, base), StorageIssueCodes.ATOMIC_MOVE_UNSUPPORTED);
    }
    assertStillAt(base);
  }

  @Test
  void unexplainedStagingIsReportedAndNeverDeleted() throws Exception {
    ManifestDocument base = commitInitial(root);
    Path mystery = root.resolve(".staging/not-a-transaction");
    Files.createDirectories(mystery);
    Files.writeString(mystery.resolve("notes.txt"), "someone's data");
    SidecarStore store = store(root, JdkStorageFileSystem.INSTANCE);
    RecoveryReport report = store.inspect();
    assertThat(report.findings())
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.code()).isEqualTo(StorageIssueCodes.RECOVERY_UNEXPLAINED);
              assertThat(f.automaticallyRecoverable()).isFalse();
            });
    try (SidecarWriter writer = writer(store)) {
      assertThat(writer.recover().needsManualAttention()).isTrue();
      assertCode(() -> writer.begin(0L), StorageIssueCodes.RECOVERY_REQUIRED);
    }
    assertThat(mystery.resolve("notes.txt")).hasContent("someone's data");
    assertThat(current()).isEqualTo(base);
  }

  @Test
  void ambiguousOutcomeNeedsAPerson() throws Exception {
    ManifestDocument base = commitInitial(root);
    SidecarStore store = store(root, JdkStorageFileSystem.INSTANCE);
    java.util.UUID id = java.util.UUID.randomUUID();
    Path dir = Files.createDirectories(root.resolve(".staging/" + id));
    TransactionRecord record =
        new TransactionRecord(
            id,
            TransactionRecord.Phase.PREPARED,
            ManifestFixtures.CHECKSUM_A,
            5L,
            6,
            List.of(),
            ManifestFixtures.CHECKSUM_B);
    Files.write(dir.resolve(TransactionRecord.FILE_NAME), record.encode());
    assertThat(store.inspect().findings())
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.code()).isEqualTo(StorageIssueCodes.RECOVERY_OUTCOME_UNKNOWN);
              assertThat(f.automaticallyRecoverable()).isFalse();
            });
    try (SidecarWriter writer = writer(store)) {
      writer.recover();
    }
    assertThat(dir.resolve(TransactionRecord.FILE_NAME)).exists();
    assertThat(current()).isEqualTo(base);
  }

  @Test
  void damagedManifestIsReportedAndPreviousRemainsReadable() throws Exception {
    ManifestDocument base = commitInitial(root);
    SidecarStore store = store(root, JdkStorageFileSystem.INSTANCE);
    try (SidecarWriter writer = writer(store)) {
      commitNext(writer, base);
    }
    Files.writeString(root.resolve("manifest.json"), "{ torn");
    RecoveryReport report = store.inspect();
    assertThat(report.findings())
        .extracting(RecoveryFinding::code)
        .containsExactly(StorageIssueCodes.RECOVERY_MANIFEST_UNREADABLE);
    assertThat(store.readPreviousManifest().document()).isEqualTo(base);
    try (SidecarWriter writer = writer(store)) {
      assertCode(() -> writer.begin(1L), StorageIssueCodes.RECOVERY_REQUIRED);
    }
  }

  /**
   * Regression (Prompt 11 audit): on a platform that can sync directories, failing to open one is
   * an error, not "unsupported". It used to be reported as unsupported, and the index and journal
   * writers then skipped the sync silently.
   */
  @Test
  void aDirectoryThatCannotBeOpenedFailsTheSyncOutsideWindows() throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    assumeTrue(
        !System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows"));
    Path dir = Files.createDirectory(root.resolve("unreadable"));
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("-wx------"));
    try {
      assumeTrue(!Files.isReadable(dir), "running with privileges that ignore permissions");
      assertThatThrownBy(() -> JdkStorageFileSystem.INSTANCE.syncDirectory(dir))
          .isInstanceOf(IOException.class);
    } finally {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }
    JdkStorageFileSystem.INSTANCE.syncDirectory(dir);
  }

  @Test
  void symlinkInsideTheSidecarIsRefused(@TempDir Path outside) throws Exception {
    ManifestDocument base = commitInitial(root);
    Files.createSymbolicLink(root.resolve("reports"), outside);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      SidecarTransaction tx = writer.begin(0L);
      assertCode(
          () ->
              tx.stage(
                  SidecarPath.of("reports/escape.json"), new ByteArrayInputStream(new byte[1]), 10),
          StorageIssueCodes.SYMLINK_REFUSED);
    }
    try (var files = Files.list(outside)) {
      assertThat(files).isEmpty();
    }
    assertStillAt(base);
  }

  @Test
  void rootReachedThroughASymlinkIsAllowed(@TempDir Path elsewhere) throws Exception {
    Path link = elsewhere.resolve("sidecar-link");
    Files.createSymbolicLink(link, root);
    ManifestDocument base = commitInitial(link);
    SidecarRoot opened = SidecarRoot.open(link);
    assertThat(opened.path()).isEqualTo(root.toRealPath());
    assertThat(SidecarStore.open(opened).readManifest().document()).isEqualTo(base);
  }

  @Test
  void rootMustBeAnExistingDirectory(@TempDir Path dir) throws Exception {
    assertCode(() -> SidecarRoot.open(dir.resolve("missing")), StorageIssueCodes.ROOT_INVALID);
    Path file = Files.writeString(dir.resolve("file"), "x");
    assertCode(() -> SidecarRoot.open(file), StorageIssueCodes.ROOT_INVALID);
  }

  @Test
  void insufficientPermissionsFailWithoutDamage() throws Exception {
    assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "POSIX permissions required");
    ManifestDocument base = commitInitial(root);
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assumeTrue(!Files.isWritable(root), "running as a user that ignores permissions (root)");
      SidecarStore store = store(root, JdkStorageFileSystem.INSTANCE);
      assertThatThrownBy(() -> writer(store))
          .isInstanceOf(StorageException.class); // cannot even create the lock
      assertThat(store.readManifest().document()).isEqualTo(base);
    } finally {
      Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
    assertStillAt(base);
  }

  @Test
  void readOnlyOperationsPerformNoWritesAndCloseEveryHandle() throws Exception {
    ManifestDocument base = commitInitial(root);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      commitNext(writer, base);
    }
    Files.createDirectories(root.resolve(".staging/leftover"));
    Files.writeString(root.resolve(".staging/leftover/junk"), "x");
    FaultyFileSystem counting = new FaultyFileSystem();
    SidecarStore store = store(root, counting);
    store.readManifest();
    store.readPreviousManifest();
    store.inspect();
    store.hasManifest();
    assertThat(counting.mutations.get()).isZero();
    assertThat(counting.openHandles.get()).isZero();
  }

  @Test
  void handlesCloseOnFailedReads() throws Exception {
    commitInitial(root);
    Files.writeString(root.resolve("manifest.json"), "{ torn");
    FaultyFileSystem counting = new FaultyFileSystem();
    SidecarStore store = store(root, counting);
    assertThatThrownBy(store::readManifest).isInstanceOf(Exception.class);
    assertThat(counting.openHandles.get()).isZero();
  }

  @Test
  void checksumsStreamAndRespectLimits() throws IOException {
    byte[] data = new byte[300_000];
    new java.util.Random(1).nextBytes(data);
    Checksums.Measured m = Checksums.sha256(new ByteArrayInputStream(data), data.length);
    assertThat(m.sizeBytes()).isEqualTo(data.length);
    assertThat(m.checksum())
        .isEqualTo(
            io.github.kjmorse2.mconeworld.domain.Checksum.ofDigest(
                io.github.kjmorse2.mconeworld.domain.Checksum.Algorithm.SHA_256, sha256(data)));
    assertCode(
        () -> Checksums.sha256(new ByteArrayInputStream(data), data.length - 1),
        StorageIssueCodes.TOO_LARGE);
    assertCode(
        () -> Checksums.readBounded(new ByteArrayInputStream(data), 10),
        StorageIssueCodes.TOO_LARGE);
    assertThat(Checksums.readBounded(new ByteArrayInputStream(data), data.length)).isEqualTo(data);
  }

  private static byte[] sha256(byte[] data) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(data);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void initialCreationRequiresNoExistingManifest() throws Exception {
    commitInitial(root);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      assertCode(() -> writer.begin(null), StorageIssueCodes.BASE_CHANGED);
    }
    assertThat(DimensionKey.OVERWORLD).isNotNull();
  }
}
