// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Version and capability negotiation. Pure functions: no I/O, no state, and no exception for an
 * incompatible peer, which is an ordinary result.
 *
 * <ul>
 *   <li><b>Version.</b> The highest version both sides support. A client that also supports newer
 *       versions than the server still gets the server's best common one. A client whose range does
 *       not overlap gets {@link Incompatible}.
 *   <li><b>Capabilities.</b> Those both sides list and that the agreed version defines. Unknown
 *       capabilities are ignored, never errors.
 * </ul>
 */
public sealed interface Negotiation {

  /**
   * Agreed on {@code version} with {@code enabled} capabilities.
   *
   * @param version the negotiated version
   * @param enabled the capabilities in effect
   */
  record Agreed(int version, CapabilitySet enabled) implements Negotiation {
    public Agreed {
      Objects.requireNonNull(enabled, "enabled");
    }
  }

  /**
   * No common version.
   *
   * @param server the server's versions
   * @param client the client's versions
   */
  record Incompatible(VersionRange server, VersionRange client) implements Negotiation {
    public Incompatible {
      Objects.requireNonNull(server, "server");
      Objects.requireNonNull(client, "client");
    }
  }

  /** The server's decision for {@code hello}. */
  static Negotiation negotiate(ProtocolSupport server, HelloMessage hello) {
    int version = server.versions().highestCommon(hello.versions());
    if (version == 0) {
      return new Incompatible(server.versions(), hello.versions());
    }
    List<Capability> enabled = new ArrayList<>();
    for (Capability c : server.capabilities().intersect(hello.capabilities()).values()) {
      int since = ProtocolSupport.sinceVersion(c);
      if (since != 0 && since <= version) {
        enabled.add(c);
      }
    }
    return new Agreed(version, CapabilitySet.of(enabled));
  }

  /**
   * The server's reply to a hello, given the active season (or {@code null}). The season is
   * included only if the season context was enabled.
   */
  static ContextMessage reply(Negotiation negotiation, @Nullable SeasonContext activeSeason) {
    return switch (negotiation) {
      case Agreed agreed ->
          new ContextMessage.Accepted(
              agreed.version(),
              agreed.enabled(),
              agreed.enabled().contains(Capability.SEASON_CONTEXT) ? activeSeason : null,
              Extensions.NONE);
      case Incompatible incompatible ->
          new ContextMessage.Incompatible(incompatible.server(), Extensions.NONE);
    };
  }

  /**
   * The client's check of a reply against the hello it sent: the version must be one it offered,
   * and only capabilities it asked for may be enabled. A reply that fails is ignored by the client
   * (it keeps showing absolute coordinates).
   *
   * @throws ProtocolException {@code UNEXPECTED_REPLY}
   */
  static ContextMessage checkReply(HelloMessage sent, ContextMessage reply)
      throws ProtocolException {
    if (reply instanceof ContextMessage.Accepted accepted) {
      if (!sent.versions().contains(accepted.version())) {
        throw new ProtocolException(
            ProtocolIssueCodes.UNEXPECTED_REPLY,
            "the server chose version " + accepted.version() + ", which the client did not offer",
            -1);
      }
      if (!accepted.enabled().isSubsetOf(sent.capabilities())) {
        throw new ProtocolException(
            ProtocolIssueCodes.UNEXPECTED_REPLY,
            "the server enabled capabilities the client did not ask for",
            -1);
      }
    }
    return reply;
  }
}
