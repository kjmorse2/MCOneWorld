// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.season;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/** Stable codes for season planning. Never rename or repurpose one. */
public final class SeasonIssueCodes {
  /** The origin is not aligned to 128 blocks; it is never rounded (root AGENTS.md §8). */
  public static final IssueCode ORIGIN_MISALIGNED = IssueCode.of("tool.season.origin_misaligned");

  /** The clear area around the origin would cross the world border. */
  public static final IssueCode OUT_OF_BOUNDS = IssueCode.of("tool.season.out_of_bounds");

  /** Generated Overworld chunks lie within the clear area. */
  public static final IssueCode OVERWORLD_OCCUPIED = IssueCode.of("tool.season.overworld_occupied");

  /** Generated Nether chunks lie within the Nether counterpart of the clear area. */
  public static final IssueCode NETHER_OCCUPIED = IssueCode.of("tool.season.nether_occupied");

  /** The clear area overlaps another season's. */
  public static final IssueCode SEASON_OVERLAP = IssueCode.of("tool.season.season_overlap");

  /** No origin satisfies every rule within the world border. */
  public static final IssueCode NO_ROOM = IssueCode.of("tool.season.no_room");

  private SeasonIssueCodes() {}
}
