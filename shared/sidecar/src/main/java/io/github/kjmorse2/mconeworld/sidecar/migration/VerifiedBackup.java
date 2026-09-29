// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.sidecar.storage.Checksums;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Evidence that a restorable copy of the pre-migration manifest exists outside the sidecar. It can
 * only be created by actually reading and checksumming the copy.
 *
 * <p>Why a copy of the manifest suffices: a manifest migration rewrites only {@code manifest.json}
 * (plus a new report file). Referenced data files are immutable and untouched (ADR 0004). Restoring
 * the copied manifest therefore restores the exact pre-migration sidecar. ({@code
 * manifest.previous.json} also keeps it, but that lives inside the sidecar, so it is not
 * independent evidence.) The {@link #location()} is machine-specific. It is never persisted: the
 * migration report stores only the backup id and checksum.
 */
public final class VerifiedBackup {
  private final BackupId id;
  private final Path location;
  private final Checksum checksum;
  private final long sizeBytes;

  private VerifiedBackup(BackupId id, Path location, Checksum checksum, long sizeBytes) {
    this.id = id;
    this.location = location;
    this.checksum = checksum;
    this.sizeBytes = sizeBytes;
  }

  /**
   * Reads {@code copy} and records its checksum and size.
   *
   * @throws IOException if it cannot be read, or holds more than {@code maxBytes}
   */
  public static VerifiedBackup verify(BackupId id, Path copy, long maxBytes) throws IOException {
    Objects.requireNonNull(id, "id");
    Path absolute = copy.toAbsolutePath().normalize();
    try (InputStream in =
        Files.newInputStream(absolute, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      Checksums.Measured measured = Checksums.sha256(in, maxBytes);
      return new VerifiedBackup(id, absolute, measured.checksum(), measured.sizeBytes());
    }
  }

  public BackupId id() {
    return id;
  }

  /** Where the verified copy is. Machine-specific: never persist it. */
  public Path location() {
    return location;
  }

  public Checksum checksum() {
    return checksum;
  }

  public long sizeBytes() {
    return sizeBytes;
  }
}
