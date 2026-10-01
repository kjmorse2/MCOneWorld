// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.store;
import static io.github.kjmorse2.mconeworld.sidecar.storage.StorageTestSupport.writer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The durable primitives of the {@code operations/} area. */
class OperationFileWriterTest {
  private static final SidecarPath PLAN = SidecarPath.of("op/plan.json");
  private static final SidecarPath JOURNAL = SidecarPath.of("op/journal.jsonl");
  private static final byte[] A = "first".getBytes(StandardCharsets.UTF_8);
  private static final byte[] B = "second".getBytes(StandardCharsets.UTF_8);

  @TempDir Path root;

  private Path file(String relative) {
    return root.resolve("operations").resolve(relative);
  }

  private static void assertCode(ThrowingRunnable action, IssueCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(StorageException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  @FunctionalInterface
  interface ThrowingRunnable {
    void run() throws Exception;
  }

  @Test
  void writeOnceCreatesAtomicallyAndIsIdempotent() throws Exception {
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      OperationFileWriter out = writer.operationFiles();
      assertThat(out.writeOnce(PLAN, A)).isTrue();
      assertThat(out.writeOnce(PLAN, A)).isFalse();
      assertCode(() -> out.writeOnce(PLAN, B), StorageIssueCodes.DATA_FILE_CONFLICT);
      assertThat(out.files().read(PLAN, 100)).isEqualTo(A);
      assertThat(out.files().list(null)).containsExactly("op");
      assertThat(out.files().list(SidecarPath.of("op"))).containsExactly("plan.json");
    }
    assertThat(Files.readAllBytes(file("op/plan.json"))).isEqualTo(A);
  }

  @Test
  void aLeftoverTemporaryFileIsReportedNeverDeleted() throws Exception {
    Files.createDirectories(file("op"));
    Files.write(file("op/plan.json.tmp"), B);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      assertCode(
          () -> writer.operationFiles().writeOnce(PLAN, A), StorageIssueCodes.RECOVERY_REQUIRED);
    }
    assertThat(Files.readAllBytes(file("op/plan.json.tmp"))).isEqualTo(B);
    assertThat(file("op/plan.json")).doesNotExist();
  }

  @Test
  void aFailedWriteRemovesOnlyItsOwnTemporaryFile() throws Exception {
    FaultyFileSystem fs = new FaultyFileSystem();
    try (SidecarWriter writer = writer(store(root, fs))) {
      fs.onWrite =
          (file, sync) -> {
            if (sync) {
              throw new IOException("injected");
            }
          };
      assertThatThrownBy(() -> writer.operationFiles().writeOnce(PLAN, A))
          .isInstanceOf(IOException.class);
      fs.onWrite = null;
      assertThat(file("op/plan.json.tmp")).doesNotExist();
      assertThat(file("op/plan.json")).doesNotExist();
      fs.atomicMoveUnsupported = true;
      assertCode(
          () -> writer.operationFiles().writeOnce(PLAN, A),
          StorageIssueCodes.ATOMIC_MOVE_UNSUPPORTED);
      assertThat(file("op/plan.json.tmp")).doesNotExist();
      assertThat(fs.openHandles.get()).isZero();
    }
  }

  @Test
  void appendChecksTheExpectedSize() throws Exception {
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      OperationFileWriter out = writer.operationFiles();
      assertCode(() -> out.append(JOURNAL, 3, A), StorageIssueCodes.BASE_CHANGED);
      out.append(JOURNAL, 0, A);
      out.append(JOURNAL, A.length, B);
      assertCode(() -> out.append(JOURNAL, A.length, B), StorageIssueCodes.BASE_CHANGED);
      assertThat(out.files().size(JOURNAL)).isEqualTo(A.length + B.length);
    }
    assertThat(Files.readString(file("op/journal.jsonl"))).isEqualTo("firstsecond");
  }

  @Test
  void replaceRequiresTheExactCurrentContent() throws Exception {
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      OperationFileWriter out = writer.operationFiles();
      out.append(JOURNAL, 0, A);
      assertCode(
          () -> out.replace(JOURNAL, SidecarStore.measure(B), B, 100),
          StorageIssueCodes.BASE_CHANGED);
      out.replace(JOURNAL, SidecarStore.measure(A), B, 100);
      assertThat(out.files().read(JOURNAL, 100)).isEqualTo(B);
    }
  }

  @Test
  void everyWriteNeedsTheLock() throws Exception {
    SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE));
    OperationFileWriter out = writer.operationFiles();
    writer.close();
    assertCode(() -> out.writeOnce(PLAN, A), StorageIssueCodes.LOCK_LOST);
    assertCode(() -> out.append(JOURNAL, 0, A), StorageIssueCodes.LOCK_LOST);
    assertThat(file("op")).doesNotExist();
  }

  @Test
  void symlinksInsideTheAreaAreRefused() throws Exception {
    Path outside = Files.createDirectories(root.resolve("elsewhere"));
    Files.createDirectories(root.resolve("operations"));
    Files.createSymbolicLink(file("op"), outside);
    try (SidecarWriter writer = writer(store(root, JdkStorageFileSystem.INSTANCE))) {
      assertCode(
          () -> writer.operationFiles().writeOnce(PLAN, A), StorageIssueCodes.SYMLINK_REFUSED);
      assertCode(
          () -> writer.operationFiles().append(JOURNAL, 0, A), StorageIssueCodes.SYMLINK_REFUSED);
    }
    try (var entries = Files.list(outside)) {
      assertThat(entries).isEmpty();
    }
  }

  @Test
  void readsAreBoundedAndReportMissingFiles() throws Exception {
    SidecarStore store = store(root, JdkStorageFileSystem.INSTANCE);
    assertThat(store.operationFiles().list(null)).isEmpty();
    assertThatThrownBy(() -> store.operationFiles().read(PLAN, 10))
        .isInstanceOf(NoSuchFileException.class);
    try (SidecarWriter writer = writer(store)) {
      writer.operationFiles().writeOnce(PLAN, B);
    }
    assertCode(() -> store.operationFiles().read(PLAN, 3), StorageIssueCodes.TOO_LARGE);
    assertThat(SidecarRoot.isReserved(SidecarPath.of("operations/x/plan.json"))).isTrue();
  }
}
