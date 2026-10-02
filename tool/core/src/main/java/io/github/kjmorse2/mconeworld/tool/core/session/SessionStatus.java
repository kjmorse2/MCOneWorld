// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.session;

import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspection;
import java.util.List;
import java.util.Objects;

/**
 * Everything the status screen shows: the world, the sidecar, and which actions can start.
 *
 * @param locations the directories inspected
 * @param world the world inspection
 * @param sidecar the sidecar as found
 * @param actions one entry per {@link SessionAction}, in declaration order
 * @param findings every finding of the world and the sidecar, sorted
 */
public record SessionStatus(
    ToolLocations locations,
    WorldInspection world,
    SidecarSnapshot sidecar,
    List<ActionAvailability> actions,
    ValidationResult findings) {
  public SessionStatus {
    Objects.requireNonNull(locations, "locations");
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(sidecar, "sidecar");
    actions = List.copyOf(actions);
    Objects.requireNonNull(findings, "findings");
  }

  /** The availability of {@code action}. */
  public ActionAvailability action(SessionAction action) {
    return actions.stream()
        .filter(a -> a.action() == action)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No availability for " + action));
  }
}
