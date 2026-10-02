// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol.codec;

import io.github.kjmorse2.mconeworld.protocol.Capability;
import io.github.kjmorse2.mconeworld.protocol.CapabilitySet;
import io.github.kjmorse2.mconeworld.protocol.Extension;
import io.github.kjmorse2.mconeworld.protocol.Extensions;
import io.github.kjmorse2.mconeworld.protocol.Identifiers;
import io.github.kjmorse2.mconeworld.protocol.ProtocolException;
import io.github.kjmorse2.mconeworld.protocol.ProtocolIssueCodes;
import io.github.kjmorse2.mconeworld.protocol.ProtocolLimits;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Reads the primitives of {@code docs/formats/protocol-v1.md} §3 from a bounded array, rejecting
 * every non-canonical form. Every length is checked against both its limit and the bytes that
 * remain before anything is allocated, so allocation is bounded by the input's size.
 */
final class WireReader {
  private final byte[] bytes;
  private int position;

  WireReader(byte[] bytes) {
    this.bytes = bytes;
  }

  int position() {
    return position;
  }

  int u8() throws ProtocolException {
    require(1);
    return bytes[position++] & 0xFF;
  }

  boolean bool() throws ProtocolException {
    int at = position;
    int value = u8();
    if (value > 1) {
      throw new ProtocolException(
          ProtocolIssueCodes.OUT_OF_RANGE, "flag " + value + " is not 0 or 1", at);
    }
    return value == 1;
  }

  /**
   * An unsigned LEB128 value of at most {@code max} (which is below 2^63), in its shortest form.
   */
  long varint(long max) throws ProtocolException {
    int at = position;
    int maxBytes = (64 - Long.numberOfLeadingZeros(max) + 6) / 7;
    long value = 0;
    for (int i = 0; ; i++) {
      if (i == Math.max(maxBytes, 1)) {
        throw new ProtocolException(ProtocolIssueCodes.MALFORMED_VARINT, "varint too long", at);
      }
      int b = u8();
      value |= (long) (b & 0x7F) << (7 * i);
      if ((b & 0x80) == 0) {
        if (b == 0 && i > 0) {
          throw new ProtocolException(
              ProtocolIssueCodes.NOT_CANONICAL, "varint not in shortest form", at);
        }
        break;
      }
    }
    // The bytes allowed can hold a little more than max; the shift above never loses bits because
    // 7 * maxBytes <= 63 for every max below 2^63.
    if (value < 0 || value > max) {
      throw new ProtocolException(
          ProtocolIssueCodes.OUT_OF_RANGE,
          "value " + Long.toUnsignedString(value) + " exceeds " + max,
          at);
    }
    return value;
  }

  int count(int max, String what) throws ProtocolException {
    int at = position;
    long value = varint(Integer.MAX_VALUE);
    if (value > max) {
      throw new ProtocolException(
          ProtocolIssueCodes.TOO_LARGE, what + " count " + value + " exceeds " + max, at);
    }
    return (int) value;
  }

  int i32() throws ProtocolException {
    require(4);
    int value = 0;
    for (int i = 0; i < 4; i++) {
      value = (value << 8) | (bytes[position++] & 0xFF);
    }
    return value;
  }

  long i64() throws ProtocolException {
    require(8);
    long value = 0;
    for (int i = 0; i < 8; i++) {
      value = (value << 8) | (bytes[position++] & 0xFF);
    }
    return value;
  }

  UUID uuid() throws ProtocolException {
    return new UUID(i64(), i64());
  }

  /** A length-prefixed, strictly well-formed UTF-8 string of at most {@code maxBytes} bytes. */
  String string(int maxBytes, String what) throws ProtocolException {
    int length = lengthPrefix(maxBytes, what);
    int at = position;
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      String text = decoder.decode(ByteBuffer.wrap(bytes, position, length)).toString();
      position += length;
      return text;
    } catch (CharacterCodingException e) {
      throw new ProtocolException(
          ProtocolIssueCodes.MALFORMED_TEXT, what + " is not well-formed UTF-8", at, e);
    }
  }

  /** A capability or extension-key identifier (the {@link Identifiers} grammar). */
  String identifier(String what) throws ProtocolException {
    int at = position;
    String text = string(ProtocolLimits.MAX_IDENTIFIER_BYTES, what);
    if (!Identifiers.isValid(text)) {
      throw new ProtocolException(ProtocolIssueCodes.INVALID_IDENTIFIER, "invalid " + what, at);
    }
    return text;
  }

  CapabilitySet capabilities() throws ProtocolException {
    int count = count(ProtocolLimits.MAX_CAPABILITIES, "capability");
    List<Capability> out = new ArrayList<>(count);
    @Nullable String previous = null;
    for (int i = 0; i < count; i++) {
      int at = position;
      String id = identifier("capability");
      requireAscending(previous, id, "capabilities", at);
      previous = id;
      out.add(new Capability(id));
    }
    return CapabilitySet.of(out);
  }

  Extensions extensions() throws ProtocolException {
    int count = count(ProtocolLimits.MAX_EXTENSIONS, "extension");
    List<Extension> out = new ArrayList<>(count);
    @Nullable String previous = null;
    for (int i = 0; i < count; i++) {
      int at = position;
      String key = identifier("extension key");
      requireAscending(previous, key, "extensions", at);
      previous = key;
      int length = lengthPrefix(ProtocolLimits.MAX_EXTENSION_BYTES, "extension value");
      byte[] value = new byte[length];
      System.arraycopy(bytes, position, value, 0, length);
      position += length;
      out.add(new Extension(key, value));
    }
    return new Extensions(out);
  }

  static void requireAscending(@Nullable String previous, String next, String what, int at)
      throws ProtocolException {
    if (previous != null && previous.compareTo(next) >= 0) {
      throw new ProtocolException(
          ProtocolIssueCodes.NOT_CANONICAL,
          what
              + " are not in strictly ascending order (\""
              + next
              + "\" after \""
              + previous
              + "\")",
          at);
    }
  }

  /** Rejects bytes left after the last field. */
  void end() throws ProtocolException {
    if (position != bytes.length) {
      throw new ProtocolException(
          ProtocolIssueCodes.TRAILING_BYTES,
          (bytes.length - position) + " bytes after the message",
          position);
    }
  }

  private int lengthPrefix(int max, String what) throws ProtocolException {
    int at = position;
    long length = varint(Integer.MAX_VALUE);
    if (length > max) {
      throw new ProtocolException(
          ProtocolIssueCodes.TOO_LARGE, what + " is " + length + " bytes; the limit is " + max, at);
    }
    require((int) length);
    return (int) length;
  }

  private void require(int n) throws ProtocolException {
    if (bytes.length - position < n) {
      throw new ProtocolException(ProtocolIssueCodes.TRUNCATED, "the payload ends early", position);
    }
  }
}
