// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@code mconeworld:context}, server to client, sent only in reply to a hello. Either {@link
 * Accepted} (the negotiated version, the enabled capabilities, and the season context if enabled
 * and active) or {@link Incompatible} (no common version; the client keeps showing absolute
 * coordinates, and nobody is disconnected).
 */
public sealed interface ContextMessage {

  /** Extensions of later versions, kept opaque. */
  Extensions extensions();

  /**
   * The server agreed on {@code version}.
   *
   * @param version the negotiated protocol version
   * @param enabled the capabilities enabled for this connection
   * @param season the active season, or {@code null} if none is active or the season context is not
   *     enabled; a season requires {@link Capability#SEASON_CONTEXT} in {@code enabled}
   * @param extensions optional fields of later versions
   */
  record Accepted(
      int version, CapabilitySet enabled, @Nullable SeasonContext season, Extensions extensions)
      implements ContextMessage {
    /**
     * @throws IllegalArgumentException if the version is out of range, or a season is present
     *     without the season-context capability
     */
    public Accepted {
      Objects.requireNonNull(enabled, "enabled");
      Objects.requireNonNull(extensions, "extensions");
      if (version < 1 || version > ProtocolLimits.MAX_PROTOCOL_VERSION) {
        throw new IllegalArgumentException("Invalid protocol version " + version);
      }
      if (season != null && !enabled.contains(Capability.SEASON_CONTEXT)) {
        throw new IllegalArgumentException("A season needs the season-context capability");
      }
    }
  }

  /**
   * The server shares no version with the client.
   *
   * @param server the versions the server supports, for the client's diagnostics
   * @param extensions optional fields of later versions
   */
  record Incompatible(VersionRange server, Extensions extensions) implements ContextMessage {
    public Incompatible {
      Objects.requireNonNull(server, "server");
      Objects.requireNonNull(extensions, "extensions");
    }
  }
}
