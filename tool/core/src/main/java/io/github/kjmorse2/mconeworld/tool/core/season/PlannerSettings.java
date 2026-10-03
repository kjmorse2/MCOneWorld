// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.season;

/**
 * How far a new season keeps from everything that exists.
 *
 * @param clearanceBlocks the half-width of the square around the origin, in Overworld blocks, that
 *     must hold no generated chunk and no other season's square; its Nether counterpart (divided by
 *     8) must hold no generated Nether chunk
 * @param y the reference Y recorded with the origins and the nominal spawn Y; the server finds the
 *     real spawn height when it applies the season (ADR 0011)
 */
public record PlannerSettings(long clearanceBlocks, int y) {
  /** 10,000 blocks of untouched terrain around the origin, reference Y 64. */
  public static final PlannerSettings DEFAULT = new PlannerSettings(10_000, 64);

  public PlannerSettings {
    if (clearanceBlocks < 128 || clearanceBlocks > 10_000_000) {
      throw new IllegalArgumentException("Clearance must be 128 to 10,000,000 blocks");
    }
  }
}
