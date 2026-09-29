// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.provenance;

import io.github.kjmorse2.mconeworld.domain.SeasonId;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The first time MCOneWorld knew about a chunk. It is either {@link InSeason} (the season is known)
 * or {@link Legacy} (the chunk predates tracking, so its season is unknown). There is deliberately
 * no way to express "legacy but in season X".
 */
public sealed interface FirstObservation {

  /** When MCOneWorld first recorded the chunk. */
  Instant observedAt();

  /** The evidence behind this claim. */
  ProvenanceEvidence evidence();

  /** The season the chunk was first observed in, or {@code null} if unknown ({@link Legacy}). */
  @Nullable SeasonId knownSeason();

  /**
   * The chunk was first observed or generated while {@code season} was active.
   *
   * @param season the season it was first observed in
   * @param observedAt when, supplied by the caller
   * @param evidence the source and confidence
   */
  record InSeason(SeasonId season, Instant observedAt, ProvenanceEvidence evidence)
      implements FirstObservation {
    public InSeason {
      Objects.requireNonNull(season, "season");
      Objects.requireNonNull(observedAt, "observedAt");
      Objects.requireNonNull(evidence, "evidence");
    }

    @Override
    public SeasonId knownSeason() {
      return season;
    }
  }

  /**
   * The chunk already existed when MCOneWorld tracking began, for example in an imported vanilla
   * world. Its season is unknown and is never guessed.
   *
   * @param observedAt when MCOneWorld discovered it
   * @param evidence how it was discovered
   */
  record Legacy(Instant observedAt, ProvenanceEvidence evidence) implements FirstObservation {
    public Legacy {
      Objects.requireNonNull(observedAt, "observedAt");
      Objects.requireNonNull(evidence, "evidence");
    }

    @Override
    public @Nullable SeasonId knownSeason() {
      return null;
    }
  }
}
