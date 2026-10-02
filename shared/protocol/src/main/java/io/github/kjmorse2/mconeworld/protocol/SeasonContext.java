// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The wire form of the active season: only what a client needs to present season-local coordinates.
 * It is a separate type from the sidecar's records, so the wire and the sidecar schemas evolve
 * independently. A dimension without an origin here has no season-local coordinates; the client
 * shows absolute ones (root AGENTS.md §14).
 *
 * @param season the active season's stable id
 * @param name its display name (presentation only; not unique)
 * @param sidecarRevision the manifest revision the context was read from, so a client can tell a
 *     cached context is stale; 0 to 2^63 - 1
 * @param origins per-dimension season origins, at most {@value ProtocolLimits#MAX_ORIGINS}, sorted
 *     and unmodifiable
 */
public record SeasonContext(
    SeasonId season,
    DisplayName name,
    long sidecarRevision,
    SortedMap<DimensionKey, DimensionOrigin> origins) {

  /**
   * @throws IllegalArgumentException if the revision is negative, there are too many origins, or an
   *     origin is filed under another dimension
   */
  public SeasonContext {
    Objects.requireNonNull(season, "season");
    Objects.requireNonNull(name, "name");
    if (sidecarRevision < 0) {
      throw new IllegalArgumentException("sidecarRevision must not be negative");
    }
    TreeMap<DimensionKey, DimensionOrigin> copy = new TreeMap<>(origins);
    if (copy.size() > ProtocolLimits.MAX_ORIGINS) {
      throw new IllegalArgumentException("At most " + ProtocolLimits.MAX_ORIGINS + " origins");
    }
    for (Map.Entry<DimensionKey, DimensionOrigin> e : copy.entrySet()) {
      if (!e.getValue().dimension().equals(e.getKey())) {
        throw new IllegalArgumentException(
            "Origin for " + e.getValue().dimension() + " filed under " + e.getKey());
      }
    }
    origins = Collections.unmodifiableSortedMap(copy);
  }

  /**
   * A context from a list of origins, which must name distinct dimensions.
   *
   * @throws IllegalArgumentException on a repeated dimension
   */
  public static SeasonContext of(
      SeasonId season,
      DisplayName name,
      long sidecarRevision,
      Collection<DimensionOrigin> origins) {
    TreeMap<DimensionKey, DimensionOrigin> map = new TreeMap<>();
    for (DimensionOrigin origin : origins) {
      if (map.put(origin.dimension(), origin) != null) {
        throw new IllegalArgumentException("Two origins for " + origin.dimension());
      }
    }
    return new SeasonContext(season, name, sidecarRevision, map);
  }
}
