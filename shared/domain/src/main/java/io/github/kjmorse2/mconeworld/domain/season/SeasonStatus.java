// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.season;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a season. Constant names are persisted, so never rename them.
 *
 * <pre>
 *   PLANNED ──activate──▶ ACTIVE ──archive──▶ ARCHIVED
 *      │
 *      └──cancel──▶ CANCELLED
 * </pre>
 *
 * <p>{@code ARCHIVED} and {@code CANCELLED} are terminal. History is append-oriented, so an
 * archived season is never reactivated by a status change. A failed activation is undone by
 * restoring the previous sidecar state through the operation journal, not by a reverse transition.
 */
public enum SeasonStatus {
  /** Created and planned, not yet played. */
  PLANNED,
  /** The one season players are currently in. There is at most one, named by the archive state. */
  ACTIVE,
  /** A past season. Its terrain and records are preserved. */
  ARCHIVED,
  /** Planned but never activated. */
  CANCELLED;

  /** Returns the statuses this status may move to directly. The returned set is a fresh copy. */
  public Set<SeasonStatus> allowedNext() {
    return switch (this) {
      case PLANNED -> EnumSet.of(ACTIVE, CANCELLED);
      case ACTIVE -> EnumSet.of(ARCHIVED);
      case ARCHIVED, CANCELLED -> EnumSet.noneOf(SeasonStatus.class);
    };
  }

  /** Returns whether a direct transition to {@code next} is permitted. */
  public boolean canTransitionTo(SeasonStatus next) {
    return allowedNext().contains(next);
  }

  /** Returns whether no further transition is possible. */
  public boolean isTerminal() {
    return allowedNext().isEmpty();
  }
}
