// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Streaming, size-bounded checksum computation and bounded reads. */
public final class Checksums {
  private static final int BUFFER = 64 * 1024;

  private Checksums() {}

  /** A checksum together with the exact number of bytes it covers. */
  public record Measured(Checksum checksum, long sizeBytes) {}

  /**
   * Streams {@code in} to the end and returns its SHA-256 and size. It never holds more than one
   * buffer in memory. The stream is not closed.
   *
   * @throws StorageException {@code TOO_LARGE} if more than {@code maxBytes} bytes are available
   */
  public static Measured sha256(InputStream in, long maxBytes) throws IOException {
    Digester digester = new Digester();
    byte[] buffer = new byte[BUFFER];
    int n;
    while ((n = in.read(buffer)) != -1) {
      digester.update(buffer, 0, n);
      if (digester.size() > maxBytes) {
        throw new StorageException(
            StorageIssueCodes.TOO_LARGE, "Input exceeds " + maxBytes + " bytes");
      }
    }
    return digester.finish();
  }

  /**
   * Reads all bytes of {@code in}, up to {@code maxBytes}. The stream is not closed.
   *
   * @throws StorageException {@code TOO_LARGE} if the stream holds more
   */
  public static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
    if (maxBytes < 0 || maxBytes > Integer.MAX_VALUE - 8) {
      throw new IllegalArgumentException("maxBytes out of range: " + maxBytes);
    }
    byte[] bytes = in.readNBytes((int) maxBytes + 1);
    if (bytes.length > maxBytes) {
      throw new StorageException(
          StorageIssueCodes.TOO_LARGE, "Input exceeds " + maxBytes + " bytes");
    }
    return bytes;
  }

  /** Incremental SHA-256 plus a byte count. */
  static final class Digester {
    private final MessageDigest digest;
    private long size;

    Digester() {
      try {
        digest = MessageDigest.getInstance(Checksum.Algorithm.SHA_256.jcaName());
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 is required on every Java platform", e);
      }
    }

    void update(byte[] bytes, int offset, int length) {
      digest.update(bytes, offset, length);
      size += length;
    }

    long size() {
      return size;
    }

    Measured finish() {
      return new Measured(Checksum.ofDigest(Checksum.Algorithm.SHA_256, digest.digest()), size);
    }
  }
}
