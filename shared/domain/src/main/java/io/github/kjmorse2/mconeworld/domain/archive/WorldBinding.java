// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.archive;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.TextRules;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Which world an archive belongs to (manifest 1.1). The seed is the stable identity: a world keeps
 * its seed for life (root AGENTS.md §2), while its folder and level name can change. Only a hash of
 * the seed is stored, so a sidecar shared for support does not reveal a server's seed.
 *
 * @param seedSha256 SHA-256 of the seed as 8 big-endian bytes ({@link #seedChecksum})
 * @param levelName the level name when bound, for display only; never used for matching
 * @param dataVersion the world's data version when bound, for diagnostics
 * @param boundAt when the binding was recorded
 */
public record WorldBinding(
    Checksum seedSha256, @Nullable String levelName, int dataVersion, Instant boundAt) {
  /** Longest level name stored, in code points. */
  public static final int MAX_LEVEL_NAME_CODE_POINTS = 256;

  /**
   * @throws InvalidDomainValueException {@code VALUE_INVALID} if the data version is negative or
   *     the checksum is not SHA-256, or {@code TEXT_INVALID} if the level name breaks {@link
   *     TextRules}
   */
  public WorldBinding {
    Objects.requireNonNull(seedSha256, "seedSha256");
    Objects.requireNonNull(boundAt, "boundAt");
    if (seedSha256.algorithm() != Checksum.Algorithm.SHA_256) {
      throw new InvalidDomainValueException(
          DomainErrorCode.VALUE_INVALID, "The seed hash must be SHA-256");
    }
    if (dataVersion < 0) {
      throw new InvalidDomainValueException(
          DomainErrorCode.VALUE_INVALID, "Data version must not be negative");
    }
    if (levelName != null) {
      String problem = TextRules.findProblem(levelName, MAX_LEVEL_NAME_CODE_POINTS);
      if (problem != null) {
        throw new InvalidDomainValueException(
            DomainErrorCode.TEXT_INVALID, "Invalid level name: " + problem);
      }
    }
  }

  /** The checksum identifying {@code seed}: SHA-256 of its 8 big-endian bytes. */
  public static Checksum seedChecksum(long seed) {
    try {
      byte[] digest =
          MessageDigest.getInstance(Checksum.Algorithm.SHA_256.jcaName())
              .digest(ByteBuffer.allocate(Long.BYTES).putLong(seed).array());
      return Checksum.ofDigest(Checksum.Algorithm.SHA_256, digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", e);
    }
  }

  /** Whether {@code seed} is this binding's world seed. */
  public boolean matchesSeed(long seed) {
    return seedSha256.equals(seedChecksum(seed));
  }

  /**
   * A level name that may be stored, or {@code null} when {@code name} breaks the text rules (it is
   * display-only, so an unusual name is dropped rather than rejected).
   */
  public static @Nullable String storableLevelName(@Nullable String name) {
    return name == null || TextRules.findProblem(name, MAX_LEVEL_NAME_CODE_POINTS) != null
        ? null
        : name;
  }
}
