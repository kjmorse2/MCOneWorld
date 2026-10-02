// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A growable byte buffer with big-endian integers and LEB128 varints. */
final class ByteSink {
  private byte[] bytes;
  private int size;

  ByteSink(int initialCapacity) {
    bytes = new byte[Math.max(16, initialCapacity)];
  }

  int size() {
    return size;
  }

  /** The internal array, valid up to {@link #size()} until the next write. */
  byte[] array() {
    return bytes;
  }

  byte[] toByteArray() {
    return Arrays.copyOf(bytes, size);
  }

  void reset() {
    size = 0;
  }

  ByteSink u8(int value) {
    ensure(1);
    bytes[size++] = (byte) value;
    return this;
  }

  ByteSink u16(int value) {
    ensure(2);
    bytes[size++] = (byte) (value >>> 8);
    bytes[size++] = (byte) value;
    return this;
  }

  ByteSink i32(int value) {
    ensure(4);
    bytes[size++] = (byte) (value >>> 24);
    bytes[size++] = (byte) (value >>> 16);
    bytes[size++] = (byte) (value >>> 8);
    bytes[size++] = (byte) value;
    return this;
  }

  ByteSink i64(long value) {
    i32((int) (value >>> 32));
    return i32((int) value);
  }

  /** Unsigned LEB128. */
  ByteSink varLong(long value) {
    ensure(10);
    long v = value;
    while ((v & ~0x7FL) != 0) {
      bytes[size++] = (byte) ((v & 0x7F) | 0x80);
      v >>>= 7;
    }
    bytes[size++] = (byte) v;
    return this;
  }

  ByteSink varInt(int value) {
    return varLong(value & 0xFFFFFFFFL);
  }

  /** Zig-zag, then LEB128: compact for signed values near zero. */
  ByteSink zigZag(long value) {
    return varLong((value << 1) ^ (value >> 63));
  }

  ByteSink bytes(byte[] data) {
    return bytes(data, 0, data.length);
  }

  ByteSink bytes(byte[] data, int offset, int length) {
    ensure(length);
    System.arraycopy(data, offset, bytes, size, length);
    size += length;
    return this;
  }

  /** A varint byte length, then UTF-8. */
  ByteSink utf(String text) {
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
