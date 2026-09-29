// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.provenance;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Everything MCOneWorld knows about one chunk's history. It is evidence, not ownership.
 *
 * <p>Invariants:
 *
 * <ul>
 *   <li>{@code activity} has at most one entry per season, and is an unmodifiable list sorted by
 *       season id for deterministic output.
 *   <li>{@code protection} is independent of every provenance field.
 *   <li>A {@link FirstObservation.Legacy} chunk may still have activity in known seasons: being
 *       visited later does not reveal when it was generated.
 * </ul>
 *
 * @param chunk the chunk this record describes
 * @param firstObservation the first observation (known season or legacy)
 * @param activity later activity, one entry per season, sorted by season id
 * @param protection explicit per-chunk protection
 */
public record ChunkProvenance(
    ChunkPosition chunk,
    FirstObservation firstObservation,
    List<SeasonActivity> activity,
    ChunkProtection protection) {

  private static final Comparator<SeasonActivity> ACTIVITY_ORDER =
      Comparator.comparing(SeasonActivity::season);

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#DUPLICATE_ID} if two activity
   *     entries name the same season
   */
  public ChunkProvenance {
    Objects.requireNonNull(chunk, "chunk");
    Objects.requireNonNull(firstObservation, "firstObservation");
    Objects.requireNonNull(protection, "protection");
    List<SeasonActivity> sorted = new ArrayList<>(activity);
    Set<SeasonId> seen = new HashSet<>();
    for (SeasonActivity entry : sorted) {
      if (!seen.add(Objects.requireNonNull(entry, "activity entry").season())) {
        throw new InvalidDomainValueException(
            DomainErrorCode.DUPLICATE_ID,
            "Chunk " + chunk + " has more than one activity entry for season " + entry.season());
      }
    }
    sorted.sort(ACTIVITY_ORDER);
    activity = List.copyOf(sorted);
  }

  /** A freshly observed chunk with no later activity and no explicit protection. */
  public static ChunkProvenance firstObserved(ChunkPosition chunk, FirstObservation first) {
    return new ChunkProvenance(chunk, first, List.of(), ChunkProtection.NONE);
  }

  /** Returns whether the chunk predates MCOneWorld tracking (season unknown). */
  public boolean isLegacy() {
    return firstObservation instanceof FirstObservation.Legacy;
  }

  /** Returns whether any activity was recorded during {@code season}. */
  public boolean hasActivityIn(SeasonId season) {
    return activity.stream().anyMatch(a -> a.season().equals(season));
  }

  /**
   * Returns the most recently observed activity, or {@code null} if none. Ties on the timestamp
   * break by season id, so the answer is deterministic.
   */
  public @Nullable SeasonActivity lastActivity() {
    return activity.stream()
        .max(
            Comparator.comparing(SeasonActivity::lastObservedAt)
                .thenComparing(SeasonActivity::season))
        .orElse(null);
  }

  /**
   * Records activity. If an entry already exists for the season, the later observation wins; an
   * older observation never overwrites a newer one.
   */
  public ChunkProvenance withActivity(SeasonActivity observation) {
    List<SeasonActivity> next = new ArrayList<>(activity.size() + 1);
    boolean merged = false;
    for (SeasonActivity existing : activity) {
      if (existing.season().equals(observation.season())) {
        next.add(
            observation.lastObservedAt().isAfter(existing.lastObservedAt())
                ? observation
                : existing);
        merged = true;
      } else {
        next.add(existing);
      }
    }
    if (!merged) {
      next.add(observation);
    }
    return new ChunkProvenance(chunk, firstObservation, next, protection);
  }

  /** Returns a copy with different explicit protection. Provenance is unchanged. */
  public ChunkProvenance withProtection(ChunkProtection newProtection) {
    return new ChunkProvenance(chunk, firstObservation, activity, newProtection);
  }
}
