// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol.codec;

import io.github.kjmorse2.mconeworld.protocol.Capability;
import io.github.kjmorse2.mconeworld.protocol.CapabilitySet;
import io.github.kjmorse2.mconeworld.protocol.Extension;
import io.github.kjmorse2.mconeworld.protocol.Extensions;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Writes the primitives of {@code docs/formats/protocol-v1.md} §3 into a growable array. Callers
 * pass only validated values, so nothing here can fail except by a programming error.
 */
final class WireWriter {
  private byte[] bytes = new byte[256];
  private int size;

  void u8(int value) {
    ensure(1);
    bytes[size++] = (byte) value;
  }

  void bool(boolean value) {
    u8(value ? 1 : 0);
  }

  /** Unsigned LEB128, shortest form. */
  void varint(long value) {
    long rest = value;
    while ((rest & ~0x7FL) != 0) {
      u8((int) ((rest & 0x7F) | 0x80));
      rest >>>= 7;
    }
    u8((int) rest);
  }

  void i32(int value) {
    for (int shift = 24; shift >= 0; shift -= 8) {
      u8(value >>> shift);
    }
  }

  void i64(long value) {
    for (int shift = 56; shift >= 0; shift -= 8) {
      u8((int) (value >>> shift));
    }
  }

  void uuid(UUID value) {
    i64(value.getMostSignificantBits());
    i64(value.getLeastSignificantBits());
  }

  void string(String value) {
    byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
    varint(utf8.length);
    raw(utf8);
  }

  void raw(byte[] value) {
    ensure(value.length);
    System.arraycopy(value, 0, bytes, size, value.length);
    size += value.length;
  }

  void extensions(Extensions extensions) {
    varint(extensions.values().size());
    for (Extension e : extensions.values()) {
      string(e.key());
      byte[] value = e.value();
      varint(value.length);
      raw(value);
    }
  }

  void capabilities(CapabilitySet capabilities) {
    varint(capabilities.values().size());
    for (Capability c : capabilities.values()) {
      string(c.id());
    }
  }

  byte[] toByteArray() {
    return Arrays.copyOf(bytes, size);
  }

  private void ensure(int more) {
    if (size + more > bytes.length) {
      bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + more));
    }
  }
}
