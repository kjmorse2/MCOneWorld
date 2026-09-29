// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import java.util.Objects;

/**
 * An algorithm-qualified content checksum in canonical text form {@code <algorithm>:<hex>}, for
 * example {@code sha256:9f86d0…}.
 *
 * <p>The algorithm is always explicit, so formats can add algorithms later without ambiguity (root
 * AGENTS.md §9). Hex is lowercase and exactly the algorithm's digest length. Upper case is rejected
 * rather than normalized, so there is exactly one textual form. Computing checksums is not a domain
 * concern; see {@code shared:sidecar}.
 *
 * @param algorithm the digest algorithm
 * @param hex lowercase hex digest of the algorithm's exact length
 */
public record Checksum(Algorithm algorithm, String hex) {

  /** Supported digest algorithms. The {@link #id()} strings are part of persistent formats. */
  public enum Algorithm {
    /** SHA-256, 32 bytes, 64 hex characters. */
    SHA_256("sha256", "SHA-256", 32);

    private final String id;
    private final String jcaName;
    private final int digestBytes;

    Algorithm(String id, String jcaName, int digestBytes) {
      this.id = id;
      this.jcaName = jcaName;
      this.digestBytes = digestBytes;
    }

    /** Stable textual id used in the {@code <algorithm>:<hex>} form, e.g. {@code sha256}. */
    public String id() {
      return id;
    }

    /** Name for {@code java.security.MessageDigest.getInstance}. */
    public String jcaName() {
      return jcaName;
    }

    /** Digest length in bytes. */
    public int digestBytes() {
      return digestBytes;
    }
  }

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#CHECKSUM_MALFORMED}
   */
  public Checksum {
    Objects.requireNonNull(algorithm, "algorithm");
    Objects.requireNonNull(hex, "hex");
    if (hex.length() != algorithm.digestBytes() * 2 || !isLowerHex(hex)) {
      throw new InvalidDomainValueException(
          DomainErrorCode.CHECKSUM_MALFORMED,
          algorithm.id()
              + " checksum must be "
              + algorithm.digestBytes() * 2
              + " lowercase hex characters, got \""
              + DomainText.truncateForMessage(hex)
              + "\"");
    }
  }

  /**
   * Creates a checksum from raw digest bytes.
   *
   * @throws InvalidDomainValueException if the length does not match the algorithm
   */
  public static Checksum ofDigest(Algorithm algorithm, byte[] digest) {
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return new Checksum(algorithm, hex.toString());
  }

  /**
   * Parses the canonical {@code <algorithm>:<hex>} form.
   *
   * @throws InvalidDomainValueException with {@link DomainErrorCode#CHECKSUM_MALFORMED}
   */
  public static Checksum parse(String text) {
    int colon = text.indexOf(':');
    if (colon > 0) {
      String id = text.substring(0, colon);
      for (Algorithm algorithm : Algorithm.values()) {
        if (algorithm.id().equals(id)) {
          return new Checksum(algorithm, text.substring(colon + 1));
        }
      }
    }
    throw new InvalidDomainValueException(
        DomainErrorCode.CHECKSUM_MALFORMED,
        "Expected <algorithm>:<hex> with a supported algorithm, got \""
            + DomainText.truncateForMessage(text)
            + "\"");
  }

  /** Returns the canonical {@code <algorithm>:<hex>} text. */
  @Override
  public String toString() {
    return algorithm.id() + ":" + hex;
  }

  private static boolean isLowerHex(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
        return false;
      }
    }
    return true;
  }
}
