// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.transition;

import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.tool.core.season.SeasonProposal;
import java.util.Objects;

/**
 * What the user asks for: the new season's name and placement.
 *
 * @param name the new season's display name
 * @param placement its origins and planned spawn, usually from {@code SeasonPlanner}; validated
 *     again when planning
 * @param acknowledgeProtectedEnd the user confirmed that protected End chunks may be archived
 */
public record TransitionRequest(
    DisplayName name, SeasonProposal placement, boolean acknowledgeProtectedEnd) {
  public TransitionRequest {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(placement, "placement");
  }
}
