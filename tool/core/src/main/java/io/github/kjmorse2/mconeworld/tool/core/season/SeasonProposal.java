// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.season;

import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import java.util.Objects;

/**
 * Where a new season starts.
 *
 * @param overworld the Overworld origin, aligned to 128 blocks
 * @param nether the paired Nether origin, exactly the Overworld origin divided by 8 (so aligned to
 *     a Nether chunk)
 * @param spawn the planned spawn: the origin's X/Z at the nominal Y; the server finds a safe height
 */
public record SeasonProposal(
    DimensionOrigin overworld, DimensionOrigin nether, BlockPosition spawn) {
  public SeasonProposal {
    Objects.requireNonNull(overworld, "overworld");
    Objects.requireNonNull(nether, "nether");
    Objects.requireNonNull(spawn, "spawn");
  }
}
