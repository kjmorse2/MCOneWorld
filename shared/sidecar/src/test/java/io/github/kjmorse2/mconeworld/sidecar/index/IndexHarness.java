// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestLimits;
import io.github.kjmorse2.mconeworld.sidecar.storage.LockOwner;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A temporary sidecar with a fault-injecting filesystem and a held writer lock, for index tests.
 */
final class IndexHarness implements AutoCloseable {
  static final UUID INDEX_ID = UUID.fromString("0b5e55ed-0000-4000-8000-00000000c0de");
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);

  final Path root;
  final IndexFaultFileSystem fs = new IndexFaultFileSystem();
  final SidecarStore store;
  final SidecarWriter writer;

  IndexHarness(Path root) throws Exception {
    this.root = root;
    Files.createDirectories(root);
    this.store = SidecarStore.open(SidecarRoot.open(root, fs), ManifestLimits.DEFAULT, fs);
    this.writer = store.openWriter(new LockOwner("index-test", "1"), CLOCK, StaleLockPolicy.REFUSE);
  }

  ChunkIndexStore indexes(ChunkIndexOptions options) {
    return ChunkIndexStore.of(store, options);
  }

  SidecarChunkIndex create(ChunkIndexOptions options) throws Exception {
    return indexes(options).create(writer, INDEX_ID);
  }

  SidecarChunkIndex open(ChunkIndexOptions options) throws Exception {
    return indexes(options).openWriter(writer);
  }

  /** The digest of every record an independent read-only reader sees. */
  String digestFromDisk() throws Exception {
    try (ChunkIndexSnapshot reader = indexes(ChunkIndexOptions.DEFAULT).openReader()) {
      return ChunkRecordText.digest(reader);
    }
  }

  @Override
  public void close() throws java.io.IOException {
    writer.close();
  }
}
