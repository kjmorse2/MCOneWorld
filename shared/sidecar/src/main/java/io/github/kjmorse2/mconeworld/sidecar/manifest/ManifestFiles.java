// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Read-only file access for manifests. Opening is strictly read-only: the file is never rewritten,
 * migrated, normalized, or repaired, and no other file is created (shared/AGENTS.md). Writing, with
 * atomic replacement and locking, belongs to sidecar storage.
 */
public final class ManifestFiles {
  private ManifestFiles() {}

  /**
   * Reads and decodes {@code manifestFile}, which must be the {@code manifest.json} inside a
   * caller-supplied sidecar root.
   *
   * @throws ManifestException if the content is invalid (the file is left exactly as it was)
   * @throws IOException if the file cannot be read
   */
  public static DecodedManifest readOnly(Path manifestFile, ManifestCodec codec)
      throws IOException, ManifestException {
    try (InputStream in = Files.newInputStream(manifestFile, StandardOpenOption.READ)) {
      return codec.decode(in);
    }
  }
}
