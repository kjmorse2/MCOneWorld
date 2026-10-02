// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.operation.InputKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity of one committed index state: which index, which checkpoint, and how much of its log. An
 * operation records {@link #pin()} under {@link #INPUT_KEY} in its precondition snapshot (ADR
 * 0006), so a plan detects that the index changed between planning and applying. This is how the
 * mutable index area takes part in change detection without a manifest checksum (ADR 0007, D2).
 *
 * @param indexId the index's identity, fixed at creation
 * @param formatVersion the version the files declare, for example {@code 1.0}
 * @param generation the checkpoint (catalog) generation
 * @param logBytes the length of the log's complete frames
 * @param loggedMutations mutations logged since the checkpoint
 * @param readOnly whether a newer minor version wrote it, so this reader must not write
 * @param catalogCrc the catalog file's CRC-32C
 * @param logTailCrc the last complete log frame's payload CRC-32C, or 0 if the log is empty
 */
public record ChunkIndexState(
    UUID indexId,
    String formatVersion,
    long generation,
    long logBytes,
    int loggedMutations,
    boolean readOnly,
    int catalogCrc,
    int logTailCrc) {

  /** The precondition key operations use for the chunk index. */
  public static final InputKey INPUT_KEY = InputKey.of("sidecar:chunk-index");

  public ChunkIndexState {
    Objects.requireNonNull(indexId, "indexId");
    Objects.requireNonNull(formatVersion, "formatVersion");
  }

  /**
   * A checksum of this state's identity. Equal pins mean the same committed state; any commit or
   * checkpoint changes it.
   */
  public Checksum pin() {
    String text =
        "mconeworld-chunk-index-state\n"
            + indexId
            + "\n"
            + generation
            + "\n"
            + logBytes
            + "\n"
            + Integer.toHexString(catalogCrc)
            + "\n"
            + Integer.toHexString(logTailCrc)
            + "\n";
    try {
      return Checksum.ofDigest(
          Checksum.Algorithm.SHA_256,
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java platform provides SHA-256", e);
    }
  }
}
