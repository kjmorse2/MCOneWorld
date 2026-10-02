// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Builds hand-written payloads for tests, independently of {@link WireWriter}. */
final class Payloads {
  private Payloads() {}

  /**
   * Concatenates parts: an {@link Integer} is one byte, a {@link String} is a one-byte length (it
   * must be under 128 bytes) plus its UTF-8, a {@code byte[]} is copied as is, and a {@link Long}
   * is eight big-endian bytes.
   */
  static byte[] of(Object... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Object part : parts) {
      switch (part) {
        case Integer b -> out.write(b);
        case String s -> {
          byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
          if (utf8.length >= 128) {
            throw new IllegalArgumentException("use raw bytes for long strings");
          }
          out.write(utf8.length);
          out.writeBytes(utf8);
        }
        case byte[] raw -> out.writeBytes(raw);
        case Long l -> {
          for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (l >>> shift));
          }
        }
        default -> throw new IllegalArgumentException("unsupported part " + part);
      }
    }
    return out.toByteArray();
  }

  /** Raw bytes from unsigned values. */
  static byte[] raw(int... values) {
    byte[] out = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = (byte) values[i];
    }
    return out;
  }
}
