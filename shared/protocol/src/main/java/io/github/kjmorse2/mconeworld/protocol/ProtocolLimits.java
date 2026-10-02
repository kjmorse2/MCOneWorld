// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

/**
 * The hard limits of protocol version 1 ({@code docs/formats/protocol-v1.md} §6). Part of the wire
 * contract: a peer may rely on them, so they change only with a new protocol version.
 *
 * <p>The payload limits are chosen so that every message the types allow fits: the largest valid
 * hello encodes to 7,323 bytes and the largest valid context to 25,293 (a season requires the
 * 25-byte season-context capability among its 32), so encoding a valid message never fails. Both
 * stay under Minecraft's 32,767-byte serverbound custom-payload limit.
 */
public final class ProtocolLimits {
  /** Largest {@code mconeworld:hello} payload, in bytes. */
  public static final int MAX_HELLO_BYTES = 8_192;

  /** Largest {@code mconeworld:context} payload, in bytes. */
  public static final int MAX_CONTEXT_BYTES = 32_768;

  /** Highest protocol version number that can be expressed. */
  public static final int MAX_PROTOCOL_VERSION = 65_535;

  /** Capabilities per message. */
  public static final int MAX_CAPABILITIES = 32;

  /** Bytes per capability or extension key. */
  public static final int MAX_IDENTIFIER_BYTES = 64;

  /** Bytes of the optional client-mod version. */
  public static final int MAX_MOD_VERSION_BYTES = 64;

  /** Dimension origins per context. */
  public static final int MAX_ORIGINS = 64;

  /** Bytes of a dimension key (the domain's limit). */
  public static final int MAX_DIMENSION_BYTES = 256;

  /** Bytes of a season display name (64 code points of at most 4 bytes each). */
  public static final int MAX_NAME_BYTES = 256;

  /** Extensions per message. */
  public static final int MAX_EXTENSIONS = 16;

  /** Bytes per extension value. */
  public static final int MAX_EXTENSION_BYTES = 256;

  private ProtocolLimits() {}
}
