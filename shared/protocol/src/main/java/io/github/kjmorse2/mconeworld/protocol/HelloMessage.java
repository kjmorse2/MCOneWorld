// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@code mconeworld:hello}, client to server: what the client supports. It carries no season,
 * origin, unlock, cost, or destination, by construction: the server is the only authority for those
 * (root AGENTS.md §15). Extensions a client adds are kept as opaque bytes and never interpreted by
 * a version 1 server.
 *
 * @param versions the protocol versions the client can speak
 * @param capabilities what the client would like enabled
 * @param clientVersion the client mod's version, for diagnostics only; printable ASCII, 1 to
 *     {@value ProtocolLimits#MAX_MOD_VERSION_BYTES} bytes, or {@code null}
 * @param extensions optional fields of later versions
 */
public record HelloMessage(
    VersionRange versions,
    CapabilitySet capabilities,
    @Nullable String clientVersion,
    Extensions extensions) {

  /**
   * @throws IllegalArgumentException if {@code clientVersion} is empty, too long, or not printable
   *     ASCII
   */
  public HelloMessage {
    Objects.requireNonNull(versions, "versions");
    Objects.requireNonNull(capabilities, "capabilities");
    Objects.requireNonNull(extensions, "extensions");
    if (clientVersion != null && !isValidClientVersion(clientVersion)) {
      throw new IllegalArgumentException("Invalid client version text");
    }
  }

  /** A version 1 hello asking for the season context. */
  public static HelloMessage v1(@Nullable String clientVersion) {
    return new HelloMessage(
        VersionRange.only(VersionRange.V1),
        CapabilitySet.of(Capability.SEASON_CONTEXT),
        clientVersion,
        Extensions.NONE);
  }

  /** Whether {@code text} is a valid client version: 1 to 64 printable ASCII characters. */
  public static boolean isValidClientVersion(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length == 0 || bytes.length > ProtocolLimits.MAX_MOD_VERSION_BYTES) {
      return false;
    }
    for (byte b : bytes) {
      if (b < 0x21 || b > 0x7E) {
        return false;
      }
    }
    return true;
  }
}
