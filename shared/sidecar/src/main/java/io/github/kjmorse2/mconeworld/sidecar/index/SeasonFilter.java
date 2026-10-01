// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import java.util.Objects;

/**
 * Selects records by their season evidence. Provenance is not ownership, so there is deliberately
 * no "belongs to season" filter: callers say which evidence they mean.
 */
public sealed interface SeasonFilter {

  /** Returns whether {@code record} matches. */
  boolean matches(ChunkProvenance record);

  /** Every record. */
  static SeasonFilter any() {
    return Any.INSTANCE;
  }

  /** Records whose first observation was in {@code season}. */
  static SeasonFilter firstObservedIn(SeasonId season) {
    return new FirstObservedIn(season);
  }

  /** Records with activity in {@code season}. */
  static SeasonFilter activeIn(SeasonId season) {
    return new ActiveIn(season);
  }

  /** Records first observed in, or active in, {@code season}. */
  static SeasonFilter touchedBy(SeasonId season) {
    return new TouchedBy(season);
  }

  /** Records that predate MCOneWorld tracking, whose first season is unknown. */
  static SeasonFilter legacy() {
    return Legacy.INSTANCE;
  }

  /** Every record. */
  enum Any implements SeasonFilter {
    INSTANCE;

    @Override
    public boolean matches(ChunkProvenance record) {
      return true;
    }
  }

  /** Legacy records ({@link FirstObservation.Legacy}). */
  enum Legacy implements SeasonFilter {
    INSTANCE;

    @Override
    public boolean matches(ChunkProvenance record) {
      return record.isLegacy();
    }
  }

  /**
   * First observed in {@code season}.
   *
   * @param season the season
   */
  record FirstObservedIn(SeasonId season) implements SeasonFilter {
    public FirstObservedIn {
      Objects.requireNonNull(season, "season");
    }

    @Override
    public boolean matches(ChunkProvenance record) {
      return season.equals(record.firstObservation().knownSeason());
    }
  }

  /**
   * Active in {@code season}.
   *
   * @param season the season
   */
  record ActiveIn(SeasonId season) implements SeasonFilter {
    public ActiveIn {
      Objects.requireNonNull(season, "season");
    }

    @Override
    public boolean matches(ChunkProvenance record) {
      return record.hasActivityIn(season);
    }
  }

  /**
   * First observed in, or active in, {@code season}.
   *
   * @param season the season
   */
  record TouchedBy(SeasonId season) implements SeasonFilter {
    public TouchedBy {
      Objects.requireNonNull(season, "season");
    }

    @Override
    public boolean matches(ChunkProvenance record) {
      return season.equals(record.firstObservation().knownSeason()) || record.hasActivityIn(season);
    }
  }
}
