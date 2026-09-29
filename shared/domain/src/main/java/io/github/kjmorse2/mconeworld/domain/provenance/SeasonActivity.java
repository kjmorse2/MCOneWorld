// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.provenance;

import io.github.kjmorse2.mconeworld.domain.SeasonId;
import java.time.Instant;
import java.util.Objects;

/**
 * Evidence that a chunk was active during {@code season}. At most one entry per season is kept: the
 * latest observation.
 *
 * @param season the season during which activity was observed
 * @param lastObservedAt the latest observation of activity in that season
 * @param evidence the source and confidence
 */
public record SeasonActivity(SeasonId season, Instant lastObservedAt, ProvenanceEvidence evidence) {
  public SeasonActivity {
    Objects.requireNonNull(season, "season");
    Objects.requireNonNull(lastObservedAt, "lastObservedAt");
    Objects.requireNonNull(evidence, "evidence");
  }
}
