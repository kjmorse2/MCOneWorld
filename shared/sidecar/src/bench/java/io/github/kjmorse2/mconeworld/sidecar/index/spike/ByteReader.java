// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import java.nio.charset.StandardCharsets;

/** Bounds-checked reader for {@link ByteWriter} output. Every overrun is corruption. */
final class ByteReader {
  private final byte[] bytes;
  private final int end;
  private int position;
  private final String what;

  ByteReader(byte[] bytes, int offset, int length, String what) {
    this.bytes = bytes;
    this.position = offset;
    this.end = offset + length;
    this.what = what;
  }

  int position() {
    return position;
  }

  int remaining() {
    return end - position;
  }

  void seek(int newPosition) throws ChunkIndexException {
    if (newPosition < 0 || newPosition > end) {
      throw SpikeCodes.corrupt(what + ": offset " + newPosition + " out of range");
    }
    position = newPosition;
  }

  int u8() throws ChunkIndexException {
    need(1);
    return bytes[position++] & 0xFF;
  }

  int i32() throws ChunkIndexException {
    need(4);
    int v =
        ((bytes[position] & 0xFF) << 24)
            | ((bytes[position + 1] & 0xFF) << 16)
            | ((bytes[position + 2] & 0xFF) << 8)
            | (bytes[position + 3] & 0xFF);
    position += 4;
    return v;
  }

  long i64() throws ChunkIndexException {
    return ((long) i32() << 32) | (i32() & 0xFFFFFFFFL);
  }

  long varLong() throws ChunkIndexException {
    long result = 0;
    for (int shift = 0; shift < 64; shift += 7) {
      int b = u8();
      result |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
    }
    throw SpikeCodes.corrupt(what + ": varint longer than 10 bytes");
  }

  int varInt(int max) throws ChunkIndexException {
    long v = varLong();
    if (v < 0 || v > max) {
      throw SpikeCodes.corrupt(what + ": value " + v + " exceeds " + max);
    }
    return (int) v;
  }

  long zigZag() throws ChunkIndexException {
    long v = varLong();
    return (v >>> 1) ^ -(v & 1);
  }

  byte[] bytes(int length) throws ChunkIndexException {
    need(length);
    byte[] out = new byte[length];
    System.arraycopy(bytes, position, out, 0, length);
    position += length;
    return out;
  }

  String utf(int maxBytes) throws ChunkIndexException {
    int length = varInt(maxBytes);
    need(length);
    String s = new String(bytes, position, length, StandardCharsets.UTF_8);
    position += length;
    return s;
  }

  private void need(int n) throws ChunkIndexException {
    if (n < 0 || end - position < n) {
      throw SpikeCodes.corrupt(what + ": truncated at offset " + position);
    }
  }
}
