// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

import java.util.Map;
import java.util.Objects;

/**
 * What one side implements: a version range and capabilities. Capabilities also have the first
 * version that defines them ({@link #sinceVersion}), so a negotiated older version never enables a
 * feature it cannot carry.
 *
 * @param versions the versions this side speaks
 * @param capabilities the capabilities this side offers
 */
public record ProtocolSupport(VersionRange versions, CapabilitySet capabilities) {
  /** This implementation: protocol version 1 with the season context. */
  public static final ProtocolSupport CURRENT =
      new ProtocolSupport(
          VersionRange.only(VersionRange.V1), CapabilitySet.of(Capability.SEASON_CONTEXT));

  private static final Map<Capability, Integer> SINCE = Map.of(Capability.SEASON_CONTEXT, 1);

  public ProtocolSupport {
    Objects.requireNonNull(versions, "versions");
    Objects.requireNonNull(capabilities, "capabilities");
  }

  /** The first protocol version that defines {@code capability}, or 0 if unknown. */
  public static int sinceVersion(Capability capability) {
    return SINCE.getOrDefault(capability, 0);
  }
}
