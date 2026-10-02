// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * A bounds-checked reader over stored bytes. Every overrun or out-of-range value is corruption,
 * reported with this source's code and scope. Callers bound every count before allocating with
 * {@link #count(int, int)}.
 */
final class ByteSource {
  private final byte[] bytes;
  private final int end;
  private int position;
  private final IssueCode corrupt;
  private final String scope;

  ByteSource(byte[] bytes, int offset, int length, IssueCode corrupt, String scope) {
    this.bytes = bytes;
    this.position = offset;
    this.end = offset + length;
    this.corrupt = corrupt;
    this.scope = scope;
  }

  int position() {
    return position;
  }

  int remaining() {
    return end - position;
  }

  String scope() {
    return scope;
  }

  /** A corruption failure with this source's code and scope. */
  ChunkIndexException corrupt(String message) {
    return new ChunkIndexException(corrupt, message, scope, null);
  }

  ChunkIndexException corrupt(String message, Throwable cause) {
    return new ChunkIndexException(corrupt, message, scope, cause);
  }

  int u8() throws ChunkIndexException {
    need(1);
    return bytes[position++] & 0xFF;
  }

  int u16() throws ChunkIndexException {
    need(2);
    int v = ((bytes[position] & 0xFF) << 8) | (bytes[position + 1] & 0xFF);
    position += 2;
    return v;
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
    throw corrupt("varint longer than 10 bytes");
  }

  /** A varint in {@code 0..max}. */
  int varInt(int max) throws ChunkIndexException {
    long v = varLong();
    if (v < 0 || v > max) {
      throw corrupt("value " + v + " outside 0.." + max);
    }
    return (int) v;
  }

  /** A varint in {@code 0..max}, as a long. */
  long varLong(long max) throws ChunkIndexException {
    long v = varLong();
    if (v < 0 || v > max) {
      throw corrupt("value " + v + " outside 0.." + max);
    }
    return v;
  }

  /**
   * A count of items that each occupy at least {@code minItemBytes}: at most {@code max} and never
   * more than the remaining bytes could hold, so a damaged count cannot force a huge allocation.
   */
  int count(int max, int minItemBytes) throws ChunkIndexException {
    int n = varInt(max);
    if ((long) n * minItemBytes > remaining()) {
      throw corrupt("count " + n + " cannot fit in the remaining " + remaining() + " bytes");
    }
    return n;
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

  void skip(int length) throws ChunkIndexException {
    need(length);
    position += length;
  }

  /** A varint byte length (at most {@code maxBytes}), then strict UTF-8. */
  String utf(int maxBytes) throws ChunkIndexException {
    int length = varInt(maxBytes);
    need(length);
    try {
      String s =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes, position, length))
              .toString();
      position += length;
      return s;
    } catch (CharacterCodingException e) {
      throw corrupt("invalid UTF-8", e);
    }
  }

  /** Fails unless every byte was consumed. */
  void requireEnd() throws ChunkIndexException {
    if (position != end) {
      throw corrupt((end - position) + " unexpected trailing bytes");
    }
  }

  private void need(int n) throws ChunkIndexException {
    if (n < 0 || end - position < n) {
      throw corrupt("truncated: " + n + " bytes needed at offset " + position);
    }
  }
}
