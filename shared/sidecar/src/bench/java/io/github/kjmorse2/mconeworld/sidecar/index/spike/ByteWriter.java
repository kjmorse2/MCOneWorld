// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A growable little byte buffer with LEB128 varints, for the spike encodings. */
final class ByteWriter {
  private byte[] bytes;
  private int size;

  ByteWriter(int initialCapacity) {
    bytes = new byte[Math.max(16, initialCapacity)];
  }

  int size() {
    return size;
  }

  byte[] toByteArray() {
    return Arrays.copyOf(bytes, size);
  }

  /** The internal array; valid up to {@link #size()} until the next write. */
  byte[] array() {
    return bytes;
  }

  void reset() {
    size = 0;
  }

  ByteWriter u8(int value) {
    ensure(1);
    bytes[size++] = (byte) value;
    return this;
  }

  ByteWriter i32(int value) {
    ensure(4);
    bytes[size++] = (byte) (value >>> 24);
    bytes[size++] = (byte) (value >>> 16);
    bytes[size++] = (byte) (value >>> 8);
    bytes[size++] = (byte) value;
    return this;
  }

  ByteWriter i64(long value) {
    i32((int) (value >>> 32));
    return i32((int) value);
  }

  /** Unsigned LEB128. */
  ByteWriter varLong(long value) {
    ensure(10);
    long v = value;
    while ((v & ~0x7FL) != 0) {
      bytes[size++] = (byte) ((v & 0x7F) | 0x80);
      v >>>= 7;
    }
    bytes[size++] = (byte) v;
    return this;
  }

  ByteWriter varInt(int value) {
    return varLong(value & 0xFFFFFFFFL);
  }

  /** Zig-zag then LEB128, for signed values near zero. */
  ByteWriter zigZag(long value) {
    return varLong((value << 1) ^ (value >> 63));
  }

  ByteWriter bytes(byte[] data) {
    return bytes(data, 0, data.length);
  }

  ByteWriter bytes(byte[] data, int offset, int length) {
    ensure(length);
    System.arraycopy(data, offset, bytes, size, length);
    size += length;
    return this;
  }

  ByteWriter utf(String text) {
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    varInt(utf8.length);
    return bytes(utf8);
  }

  private void ensure(int extra) {
    if (size + extra > bytes.length) {
      bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + extra));
    }
  }
}
