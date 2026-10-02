// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestLimits;
import io.github.kjmorse2.mconeworld.sidecar.storage.JdkStorageFileSystem;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageFileSystem;
import java.nio.file.Path;

/** Read-only index stores over an existing sidecar, without taking the writer lock. */
final class IndexStores {
  private IndexStores() {}

  static ChunkIndexStore readOnly(Path root) throws Exception {
    return readOnly(root, JdkStorageFileSystem.INSTANCE);
  }

  static ChunkIndexStore readOnly(Path root, StorageFileSystem fs) throws Exception {
    return ChunkIndexStore.of(
        SidecarStore.open(SidecarRoot.open(root, fs), ManifestLimits.DEFAULT, fs));
  }
}
