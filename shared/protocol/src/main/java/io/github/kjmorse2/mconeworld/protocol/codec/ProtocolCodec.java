// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol.codec;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.protocol.Capability;
import io.github.kjmorse2.mconeworld.protocol.CapabilitySet;
import io.github.kjmorse2.mconeworld.protocol.ContextMessage;
import io.github.kjmorse2.mconeworld.protocol.Extensions;
import io.github.kjmorse2.mconeworld.protocol.HelloMessage;
import io.github.kjmorse2.mconeworld.protocol.ProtocolException;
import io.github.kjmorse2.mconeworld.protocol.ProtocolIssueCodes;
import io.github.kjmorse2.mconeworld.protocol.ProtocolLimits;
import io.github.kjmorse2.mconeworld.protocol.SeasonContext;
import io.github.kjmorse2.mconeworld.protocol.VersionRange;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Encodes and decodes the protocol version 1 payloads exactly as {@code
 * docs/formats/protocol-v1.md} specifies. The I/O boundary is a plain byte array (or a {@link
 * ByteBuffer}'s remaining bytes): a platform adapter copies the custom-payload body out of its
 * packet buffer and hands it here, so no platform type enters this module.
 *
 * <ul>
 *   <li><b>Encoding</b> is deterministic: equal messages give identical bytes however they were
 *       built, and every valid message fits its payload limit, so encoding never fails.
 *   <li><b>Decoding</b> accepts only the canonical encoding of a valid message: one input, one
 *       meaning. Anything else (truncated, trailing, oversized, overlong varint, malformed UTF-8,
 *       bad identifier, out-of-order or repeated set entry, out-of-range number) throws {@link
 *       ProtocolException} and nothing else. Hence {@code encode(decode(b))} equals {@code b} for
 *       every accepted {@code b}. Decoded values are immutable.
 * </ul>
 */
public final class ProtocolCodec {
  /** The layout byte that starts every version 1 payload. */
  public static final int LAYOUT_V1 = 1;

  static final int KIND_INCOMPATIBLE = 0;
  static final int KIND_ACCEPTED = 1;

  private ProtocolCodec() {}

  /** The bytes of a {@code mconeworld:hello} payload. */
  public static byte[] encodeHello(HelloMessage hello) {
    WireWriter w = new WireWriter();
    w.u8(LAYOUT_V1);
    w.varint(hello.versions().min());
    w.varint(hello.versions().max());
    String clientVersion = hello.clientVersion();
    w.bool(clientVersion != null);
    if (clientVersion != null) {
      w.string(clientVersion);
    }
    w.capabilities(hello.capabilities());
    w.extensions(hello.extensions());
    return w.toByteArray();
  }

  /** The bytes of a {@code mconeworld:context} payload. */
  public static byte[] encodeContext(ContextMessage context) {
    WireWriter w = new WireWriter();
    w.u8(LAYOUT_V1);
    switch (context) {
      case ContextMessage.Accepted accepted -> {
        w.u8(KIND_ACCEPTED);
        w.varint(accepted.version());
        w.capabilities(accepted.enabled());
        SeasonContext season = accepted.season();
        w.bool(season != null);
        if (season != null) {
          w.uuid(season.season().value());
          w.string(season.name().value());
          w.varint(season.sidecarRevision());
          w.varint(season.origins().size());
          for (DimensionOrigin origin : season.origins().values()) {
            w.string(origin.dimension().namespacedId());
            w.i64(origin.x());
            w.i32(origin.y());
            w.i64(origin.z());
          }
        }
      }
      case ContextMessage.Incompatible incompatible -> {
        w.u8(KIND_INCOMPATIBLE);
        w.varint(incompatible.server().min());
        w.varint(incompatible.server().max());
      }
    }
    w.extensions(context.extensions());
    return w.toByteArray();
  }

  /**
   * Decodes a hello.
   *
   * @throws ProtocolException if {@code payload} is not the canonical encoding of a valid hello
   */
  public static HelloMessage decodeHello(byte[] payload) throws ProtocolException {
    requireSize(payload.length, ProtocolLimits.MAX_HELLO_BYTES, "hello");
    WireReader r = new WireReader(payload);
    layout(r);
    VersionRange versions = versionRange(r);
    String clientVersion = null;
    if (r.bool()) {
      int at = r.position();
      clientVersion = r.string(ProtocolLimits.MAX_MOD_VERSION_BYTES, "client version");
      if (!HelloMessage.isValidClientVersion(clientVersion)) {
        throw new ProtocolException(
            ProtocolIssueCodes.MALFORMED_TEXT,
            "client version must be 1 or more printable ASCII characters",
            at);
      }
    }
    CapabilitySet capabilities = r.capabilities();
    Extensions extensions = r.extensions();
    r.end();
    return new HelloMessage(versions, capabilities, clientVersion, extensions);
  }

  /** Decodes a hello from the remaining bytes of {@code payload}, consuming them. */
  public static HelloMessage decodeHello(ByteBuffer payload) throws ProtocolException {
    return decodeHello(remaining(payload, ProtocolLimits.MAX_HELLO_BYTES, "hello"));
  }

  /**
   * Decodes a context.
   *
   * @throws ProtocolException if {@code payload} is not the canonical encoding of a valid context
   */
  public static ContextMessage decodeContext(byte[] payload) throws ProtocolException {
    requireSize(payload.length, ProtocolLimits.MAX_CONTEXT_BYTES, "context");
    WireReader r = new WireReader(payload);
    layout(r);
    int kindAt = r.position();
    int kind = r.u8();
    ContextMessage message;
    if (kind == KIND_ACCEPTED) {
      int versionAt = r.position();
      int version = (int) r.varint(ProtocolLimits.MAX_PROTOCOL_VERSION);
      if (version < 1) {
        throw new ProtocolException(ProtocolIssueCodes.OUT_OF_RANGE, "version 0", versionAt);
      }
      CapabilitySet enabled = r.capabilities();
      int seasonAt = r.position();
      SeasonContext season = r.bool() ? season(r) : null;
      if (season != null && !enabled.contains(Capability.SEASON_CONTEXT)) {
        throw new ProtocolException(
            ProtocolIssueCodes.OUT_OF_RANGE,
            "a season is present but the season context is not enabled",
            seasonAt);
      }
      message = new ContextMessage.Accepted(version, enabled, season, r.extensions());
    } else if (kind == KIND_INCOMPATIBLE) {
      VersionRange server = versionRange(r);
      message = new ContextMessage.Incompatible(server, r.extensions());
    } else {
      throw new ProtocolException(
          ProtocolIssueCodes.OUT_OF_RANGE, "unknown context kind " + kind, kindAt);
    }
    r.end();
    return message;
  }

  /** Decodes a context from the remaining bytes of {@code payload}, consuming them. */
  public static ContextMessage decodeContext(ByteBuffer payload) throws ProtocolException {
    return decodeContext(remaining(payload, ProtocolLimits.MAX_CONTEXT_BYTES, "context"));
  }

  private static SeasonContext season(WireReader r) throws ProtocolException {
    int at = r.position();
    UUID uuid = r.uuid();
    if (uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() == 0) {
      throw new ProtocolException(ProtocolIssueCodes.OUT_OF_RANGE, "nil season id", at);
    }
    SeasonId id = SeasonId.of(uuid);
    at = r.position();
    String nameText = r.string(ProtocolLimits.MAX_NAME_BYTES, "season name");
    DisplayName name;
    try {
      name = DisplayName.of(nameText);
    } catch (InvalidDomainValueException e) {
      throw new ProtocolException(
          ProtocolIssueCodes.MALFORMED_TEXT, "invalid season name: " + e.getMessage(), at, e);
    }
    long revision = r.varint(Long.MAX_VALUE);
    int count = r.count(ProtocolLimits.MAX_ORIGINS, "origin");
    List<DimensionOrigin> origins = new ArrayList<>(count);
    @Nullable String previous = null;
    for (int i = 0; i < count; i++) {
      at = r.position();
      String dimensionText = r.string(ProtocolLimits.MAX_DIMENSION_BYTES, "dimension");
      if (!DimensionKey.isValid(dimensionText)) {
        throw new ProtocolException(ProtocolIssueCodes.INVALID_IDENTIFIER, "invalid dimension", at);
      }
      WireReader.requireAscending(previous, dimensionText, "origins", at);
      previous = dimensionText;
      int xAt = r.position();
      long x = r.i64();
      int y = r.i32();
      long z = r.i64();
      if (!CoordinateLimits.isRepresentableBlockXZ(x)
          || !CoordinateLimits.isRepresentableBlockXZ(z)) {
        throw new ProtocolException(
            ProtocolIssueCodes.OUT_OF_RANGE, "origin outside the representable block range", xAt);
      }
      origins.add(new DimensionOrigin(DimensionKey.parse(dimensionText), x, y, z));
    }
    return SeasonContext.of(id, name, revision, origins);
  }

  private static void layout(WireReader r) throws ProtocolException {
    int layout = r.u8();
    if (layout != LAYOUT_V1) {
      throw new ProtocolException(
          ProtocolIssueCodes.UNSUPPORTED_LAYOUT, "unknown layout " + layout, 0);
    }
  }

  private static VersionRange versionRange(WireReader r) throws ProtocolException {
    int at = r.position();
    int min = (int) r.varint(ProtocolLimits.MAX_PROTOCOL_VERSION);
    int max = (int) r.varint(ProtocolLimits.MAX_PROTOCOL_VERSION);
    if (min < 1 || max < min) {
      throw new ProtocolException(
          ProtocolIssueCodes.OUT_OF_RANGE, "invalid version range " + min + ".." + max, at);
    }
    return new VersionRange(min, max);
  }

  private static void requireSize(int size, int max, String what) throws ProtocolException {
    if (size > max) {
      throw new ProtocolException(
          ProtocolIssueCodes.TOO_LARGE,
          what + " payload is " + size + " bytes; the limit is " + max,
          -1);
    }
  }

  private static byte[] remaining(ByteBuffer payload, int max, String what)
      throws ProtocolException {
    requireSize(payload.remaining(), max, what);
    byte[] bytes = new byte[payload.remaining()];
    payload.get(bytes);
    return bytes;
  }
}
