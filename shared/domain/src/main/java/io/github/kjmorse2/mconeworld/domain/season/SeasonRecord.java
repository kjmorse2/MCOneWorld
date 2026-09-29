// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.season;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One season: identity, display name, creation time, lifecycle status, per-dimension origins, and
 * absolute spawn.
 *
 * <p>Invariants enforced at construction:
 *
 * <ul>
 *   <li>Each origin is stored under its own dimension key. A dimension may have no origin, and
 *       clients then show absolute coordinates for it (root AGENTS.md §8). The End never inherits
 *       the Overworld origin.
 *   <li>{@code origins} is an unmodifiable copy sorted by dimension key. Later changes to the
 *       caller's map have no effect.
 *   <li>{@code spawn} is absolute and dimension-qualified.
 *   <li>{@code createdAt} is supplied by the caller. Nothing here reads the clock.
 * </ul>
 *
 * <p>Instances are immutable. "Changes" return new records with the same {@link #id()}, so renaming
 * never changes identity.
 *
 * @param id stable identity
 * @param name non-unique display name
 * @param createdAt when the season was created, supplied by the caller
 * @param status lifecycle status. Whether it is the active season is decided by the archive state.
 * @param origins season origins by dimension; may be empty
 * @param spawn absolute season spawn
 */
public record SeasonRecord(
    SeasonId id,
    DisplayName name,
    Instant createdAt,
    SeasonStatus status,
    SortedMap<DimensionKey, DimensionOrigin> origins,
    BlockPosition spawn) {

  /**
   * @throws InvalidDomainValueException with {@code DIMENSION_MISMATCH} if an origin is keyed under
   *     another dimension
   */
  public SeasonRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(spawn, "spawn");
    origins = copyOrigins(origins);
  }

  /**
   * A new {@link SeasonStatus#PLANNED} season with no origins. Clearly named construction API; add
   * origins with {@link #withOrigin(DimensionOrigin)}.
   */
  public static SeasonRecord planned(
      SeasonId id, DisplayName name, Instant createdAt, BlockPosition spawn) {
    return new SeasonRecord(id, name, createdAt, SeasonStatus.PLANNED, new TreeMap<>(), spawn);
  }

  /** Returns the origin for {@code dimension}, if this season defines one. */
  public Optional<DimensionOrigin> origin(DimensionKey dimension) {
    return Optional.ofNullable(origins.get(dimension));
  }

  /** Returns a copy with {@code origin} set for its dimension, replacing any previous one. */
  public SeasonRecord withOrigin(DimensionOrigin origin) {
    SortedMap<DimensionKey, DimensionOrigin> next = new TreeMap<>(origins);
    next.put(origin.dimension(), origin);
    return new SeasonRecord(id, name, createdAt, status, next, spawn);
  }

  /** Returns a copy with no origin for {@code dimension}. */
  public SeasonRecord withoutOrigin(DimensionKey dimension) {
    SortedMap<DimensionKey, DimensionOrigin> next = new TreeMap<>(origins);
    next.remove(dimension);
    return new SeasonRecord(id, name, createdAt, status, next, spawn);
  }

  /** Returns a renamed copy. The identity is unchanged. */
  public SeasonRecord renamed(DisplayName newName) {
    return new SeasonRecord(id, newName, createdAt, status, origins, spawn);
  }

  /**
   * Returns a copy in status {@code next}.
   *
   * @throws InvalidDomainValueException with {@link DomainErrorCode#SEASON_TRANSITION_ILLEGAL} if
   *     {@code status.canTransitionTo(next)} is false
   */
  public SeasonRecord transitionTo(SeasonStatus next) {
    if (!status.canTransitionTo(next)) {
      throw new InvalidDomainValueException(
          DomainErrorCode.SEASON_TRANSITION_ILLEGAL,
          "Season " + id + " cannot move from " + status + " to " + next);
    }
    return new SeasonRecord(id, name, createdAt, next, origins, spawn);
  }

  private static SortedMap<DimensionKey, DimensionOrigin> copyOrigins(
      Map<DimensionKey, DimensionOrigin> origins) {
    TreeMap<DimensionKey, DimensionOrigin> copy = new TreeMap<>();
    for (Map.Entry<DimensionKey, DimensionOrigin> entry : origins.entrySet()) {
      DimensionKey key = Objects.requireNonNull(entry.getKey(), "origin key");
      DimensionOrigin origin = Objects.requireNonNull(entry.getValue(), "origin");
      if (!key.equals(origin.dimension())) {
        throw new InvalidDomainValueException(
            DomainErrorCode.DIMENSION_MISMATCH,
            "Origin for " + origin.dimension() + " stored under key " + key);
      }
      copy.put(key, origin);
    }
    return Collections.unmodifiableSortedMap(copy);
  }
}
