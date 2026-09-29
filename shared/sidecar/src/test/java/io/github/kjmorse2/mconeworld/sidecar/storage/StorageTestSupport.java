// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.archiveId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.instant;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.sidecar.manifest.FileReference;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestLimits;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Shared setup: a committed revision-0 sidecar with one data file, and a revision-1 candidate. */
final class StorageTestSupport {
  static final LockOwner OWNER = new LockOwner("mconeworld-test", "0.0.0");
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  static final SidecarPath INDEX_V1 = SidecarPath.of("indexes/overworld-r1.idx");
  static final SidecarPath INDEX_V2 = SidecarPath.of("indexes/overworld-r2.idx");
  static final byte[] CONTENT_V1 = "index content revision one".getBytes(StandardCharsets.UTF_8);
  static final byte[] CONTENT_V2 =
      "index content revision two, a little longer".getBytes(StandardCharsets.UTF_8);

  private StorageTestSupport() {}

  static SidecarStore store(Path root, StorageFileSystem fs) throws StorageException {
    return SidecarStore.open(SidecarRoot.open(root, fs), ManifestLimits.DEFAULT, fs);
  }

  static SidecarWriter writer(SidecarStore store) throws StorageException {
    return store.openWriter(OWNER, CLOCK, StaleLockPolicy.REFUSE);
  }

  /** Commits revision 0: an empty archive plus INDEX_V1. */
  static ManifestDocument commitInitial(Path root) throws Exception {
    SidecarStore store = store(root, JdkStorageFileSystem.INSTANCE);
    try (SidecarWriter writer = writer(store)) {
      SidecarTransaction tx = writer.begin(null);
      StagedFile staged = tx.stage(INDEX_V1, new ByteArrayInputStream(CONTENT_V1), 1 << 20);
      ManifestDocument doc =
          ManifestDocument.create(archiveId(1), instant(0))
              .withFile(
                  new FileReference(
                      "chunk-index",
                      DimensionKey.OVERWORLD,
                      staged.target(),
                      staged.checksum(),
                      staged.sizeBytes()));
      tx.commit(doc);
      return doc;
    }
  }

  /** Stages INDEX_V2 and commits revision base+1 referencing it instead of INDEX_V1. */
  static CommitResult commitNext(SidecarWriter writer, ManifestDocument base) throws Exception {
    SidecarTransaction tx = writer.begin(base.revision());
    StagedFile staged = tx.stage(INDEX_V2, new ByteArrayInputStream(CONTENT_V2), 1 << 20);
    return tx.commit(next(base, staged));
  }

  static ManifestDocument next(ManifestDocument base, StagedFile staged) {
    ManifestDocument withoutOld =
        new ManifestDocument(
            base.schemaVersion(),
            base.archiveId(),
            base.createdAt(),
            base.revision(),
            base.state(),
            base.backupPaths(),
            java.util.List.of(),
            base.features(),
            base.preserved());
    return withoutOld
        .withFile(
            new FileReference(
                "chunk-index",
                DimensionKey.OVERWORLD,
                staged.target(),
                staged.checksum(),
                staged.sizeBytes()))
        .nextRevision();
  }
}
