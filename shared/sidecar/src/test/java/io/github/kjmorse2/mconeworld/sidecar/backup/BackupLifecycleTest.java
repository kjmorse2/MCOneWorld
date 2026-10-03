// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.archive.BackupRecord;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.fixtures.DomainFixtures;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupEntryPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupFileWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupFiles;
import io.github.kjmorse2.mconeworld.sidecar.storage.Checksums;
import io.github.kjmorse2.mconeworld.sidecar.storage.LockOwner;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarTransaction;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageFileSystem;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageIssueCodes;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Write a backup, verify it, record it, and catch every kind of damage afterwards. */
class BackupLifecycleTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);
  private static final BackupId ID = DomainFixtures.backupId(7);
  private static final Map<String, String> FILES = new LinkedHashMap<>();

  static {
    FILES.put("world/level.dat", "level");
    FILES.put("world/datapacks/afk display v1.1.15 (MC 1.21-1.21.11).zip", "zip bytes");
    FILES.put("world/dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat", "fight");
    FILES.put("sidecar/manifest.json", "{}");
  }

  @TempDir Path root;
  private SidecarStore store;

  @BeforeEach
  void sidecar() throws Exception {
    store = SidecarStore.open(SidecarRoot.open(root));
    try (SidecarWriter writer = writer()) {
      writer
          .begin(null)
          .commit(ManifestDocument.create(DomainFixtures.archiveId(1), DomainFixtures.EPOCH));
    }
  }

  private SidecarWriter writer() throws Exception {
    return store.openWriter(new LockOwner("backup-test", "0"), CLOCK, StaleLockPolicy.REFUSE);
  }

  /** Writes the payload and returns the manifest describing it. */
  private BackupManifest writePayload(SidecarWriter writer) throws Exception {
    List<BackupEntry> entries = new ArrayList<>();
    try (BackupFileWriter out = writer.openBackupWriter(ID)) {
      out.createDirectory();
      for (Map.Entry<String, String> f : FILES.entrySet()) {
        byte[] bytes = f.getValue().getBytes(StandardCharsets.UTF_8);
        try (StorageFileSystem.WritableFile file = out.createNew(BackupEntryPath.of(f.getKey()))) {
          file.write(bytes, 0, bytes.length);
          file.sync();
        }
        entries.add(
            new BackupEntry(
                BackupEntryPath.of(f.getKey()),
                bytes.length,
                Checksums.sha256(new ByteArrayInputStream(bytes), bytes.length).checksum()));
      }
      out.syncDirectories();
    }
    return new BackupManifest(ID, BackupKind.FULL, CLOCK.instant(), null, 4903, 0L, entries);
  }

  /** Writes, verifies, and records the backup; returns its record. */
  private BackupRecord recorded() throws Exception {
    try (SidecarWriter writer = writer()) {
      BackupManifest manifest = writePayload(writer);
      BackupVerification payload =
          new BackupVerifier().verifyPayload(store.backupFiles(), manifest);
      assertThat(payload.issues().issues()).isEmpty();
      ManifestDocument base = store.readManifest().document();
      SidecarTransaction tx = writer.begin(base.revision());
      ManifestDocument next = BackupRecords.stageAndRecord(tx, base, payload, null, null);
      tx.commit(next.nextRevision());
    }
    return store.readManifest().document().state().backups().getFirst();
  }

  private BackupVerification verify(BackupRecord record) throws Exception {
    return new BackupVerifier().verify(store.backupFiles(), ID, record.checksum());
  }

  private static List<IssueCode> codes(BackupVerification v) {
    return v.issues().issues().stream().map(ValidationIssue::code).toList();
  }

  private Path payload(String entry) {
    return root.resolve("backups").resolve(ID.toString()).resolve(entry);
  }

  @Test
  void aRecordedBackupVerifiesAndIsReferencedByItsManifestChecksum() throws Exception {
    BackupRecord record = recorded();
    ManifestDocument doc = store.readManifest().document();
    assertThat(doc.backupPaths().get(ID)).isEqualTo(BackupFiles.manifestPath(ID));
    byte[] json = Files.readAllBytes(root.resolve(BackupFiles.manifestPath(ID).value()));
    assertThat(record.checksum())
        .isEqualTo(Checksums.sha256(new ByteArrayInputStream(json), json.length).checksum());
    assertThat(record.sizeBytes()).isEqualTo(json.length);
    BackupVerification verification = verify(record);
    assertThat(verification.verified()).isTrue();
    assertThat(verification.manifest().entries()).hasSize(FILES.size());
    assertThat(store.inspect().findings()).isEmpty();
  }

  @Test
  void everyKindOfDamageIsFound() throws Exception {
    BackupRecord record = recorded();
    Files.writeString(payload("world/level.dat"), "LEVEL");
    Files.delete(payload("sidecar/manifest.json"));
    Files.writeString(payload("world/extra.txt"), "x");
    Files.writeString(payload("stray"), "x");
    assertThat(codes(verify(record)))
        .containsExactlyInAnyOrder(
            BackupIssueCodes.ENTRY_CHANGED,
            BackupIssueCodes.ENTRY_MISSING,
            BackupIssueCodes.ENTRY_UNEXPECTED,
            BackupIssueCodes.ENTRY_UNEXPECTED);

    Path json = root.resolve(BackupFiles.manifestPath(ID).value());
    Files.writeString(
        json,
        Files.readString(json)
            .replace("\"sourceDataVersion\": 4903", "\"sourceDataVersion\": 4904"));
    assertThat(codes(verify(record))).containsExactly(BackupIssueCodes.MANIFEST_CHANGED);
  }

  @Test
  void aSymlinkInABackupIsRefused() throws Exception {
    BackupRecord record = recorded();
    Files.createSymbolicLink(payload("world/link"), root.resolve("manifest.json"));
    assertThatThrownBy(() -> verify(record))
        .isInstanceOfSatisfying(
            StorageException.class,
            e -> assertThat(e.code()).isEqualTo(StorageIssueCodes.SYMLINK_REFUSED));
  }

  @Test
  void anUnrecordedBackupIsPartialAndOnlyItCanBeDeleted() throws Exception {
    try (SidecarWriter writer = writer()) {
      BackupManifest manifest = writePayload(writer);
      SidecarTransaction tx = writer.begin(store.readManifest().document().revision());
      BackupRecords.stageAndRecord(
          tx,
          store.readManifest().document(),
          new BackupVerifier().verifyPayload(store.backupFiles(), manifest),
          null,
          null);
      tx.abort(); // interrupted before the commit point
    }
    assertThat(store.readManifest().document().state().backups()).isEmpty();
    assertThat(codes(new BackupVerifier().verify(store.backupFiles(), ID, null)))
        .containsExactly(BackupIssueCodes.PARTIAL);
    assertThat(store.inspect().findings()).isEmpty();
    try (SidecarWriter writer = writer();
        BackupFileWriter out = writer.openBackupWriter(ID)) {
      out.deletePartial();
    }
    assertThat(store.backupFiles().exists(ID)).isFalse();

    BackupRecord record = recorded();
    try (SidecarWriter writer = writer();
        BackupFileWriter out = writer.openBackupWriter(ID)) {
      assertThatThrownBy(out::deletePartial)
          .isInstanceOfSatisfying(
              StorageException.class,
              e -> assertThat(e.code()).isEqualTo(StorageIssueCodes.DATA_FILE_CONFLICT));
      assertThatThrownBy(out::createDirectory)
          .isInstanceOfSatisfying(
              StorageException.class,
              e -> assertThat(e.code()).isEqualTo(StorageIssueCodes.DATA_FILE_CONFLICT));
    }
    assertThat(verify(record).verified()).isTrue();
  }

  @Test
  void onlyAVerifiedBackupCanBeRecordedAndOneIsWrittenAtATime() throws Exception {
    try (SidecarWriter writer = writer()) {
      BackupManifest manifest = writePayload(writer);
      Files.writeString(payload("world/level.dat"), "changed after copying");
      BackupVerification damaged =
          new BackupVerifier().verifyPayload(store.backupFiles(), manifest);
      assertThat(codes(damaged)).containsExactly(BackupIssueCodes.ENTRY_CHANGED);
      SidecarTransaction tx = writer.begin(store.readManifest().document().revision());
      assertThatThrownBy(
              () ->
                  BackupRecords.stageAndRecord(
                      tx, store.readManifest().document(), damaged, null, null))
          .isInstanceOf(IllegalArgumentException.class);
      tx.abort();
      try (BackupFileWriter first = writer.openBackupWriter(DomainFixtures.backupId(8))) {
        assertThat(first.id()).isEqualTo(DomainFixtures.backupId(8));
        assertThatThrownBy(() -> writer.openBackupWriter(DomainFixtures.backupId(9)))
            .isInstanceOf(IllegalStateException.class);
      }
      writer.openBackupWriter(DomainFixtures.backupId(9)).close();
    }
  }
}
